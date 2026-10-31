package com.jacekgajek.kapelusz.server.bands

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.withPicture
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.server.Api
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.Call
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.badRequestIf
import com.jacekgajek.kapelusz.server.busy
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.history.HistoryLog
import com.jacekgajek.kapelusz.server.ok
import com.jacekgajek.kapelusz.server.retryOnConflict
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.users.UserRecords
import com.jacekgajek.kapelusz.server.utf8Size

/** A band's state: creating, reading, changing it by operations (push), and deleting it. */
internal class BandHandlers(
    private val store: BlobStore,
    private val clock: () -> Long,
    private val maxBandBytes: Int,
    private val bands: BandAccess,
    private val users: UserRecords,
    private val history: HistoryLog,
    private val changed: BandChanged,
) {
    fun create(call: Call): HttpResponse {
        val user = call.user
        val request = call.body(CreateBandRequest.serializer())
        badRequestIf(request.name.isBlank() || request.ownerName.isBlank()) { "Band and member names are required" }
        // The same limits as every change: ids that become storage keys, names of sensible length.
        StorageKeys.safe(request.bandId)
        users.requireRoomForBand(user.userId, request.bandId)
        val draft = Band.create(request.bandId, request.name.trim(), request.currency, request.ownerMemberId, request.ownerName.trim(), user.userId)
        Engine.validateNew(draft)?.let { throw ApiError(400, ApiErrors.BAD_REQUEST, it.message) }
        badRequestIf(!request.currency.matches(Regex("[A-Z]{3}"))) { "Currency must be an ISO code" }
        val band = Band.create(request.bandId, request.name.trim(), request.currency, request.ownerMemberId, request.ownerName.trim(), user.userId)
            .withPicture(user.userId, users.pictureOf(user.userId))
        val etag = try {
            store.put(StorageKeys.band(band.id), encode(Band.serializer(), band), ifNoneMatch = true)
        } catch (e: PreconditionFailed) {
            // A retried create of the caller's own band is fine; anything else is a conflict.
            val (existing, existingEtag) = bands.load(band.id) ?: throw ApiError(409, ApiErrors.CONFLICT, "Band id is taken")
            if (existing.owner?.userId != user.userId) throw ApiError(409, ApiErrors.CONFLICT, "Band id is taken")
            users.addBandToUser(user.userId, band.id)
            return ok(BandStateResponse.serializer(), BandStateResponse(existingEtag, existing))
        }
        users.addBandToUser(user.userId, band.id)
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band))
    }

    fun state(call: Call): HttpResponse {
        val user = call.user
        val bandId = call.param("bandId")
        val ifNoneMatch = call.header("if-none-match")
        val (band, etag) = bands.loadForMember(user, bandId)
        if (ifNoneMatch == etag) return HttpResponse(304, headers = mapOf("etag" to etag))
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band), mapOf("etag" to etag))
    }

    fun push(call: Call): HttpResponse {
        val user = call.user
        val bandId = call.param("bandId")
        val request = call.body(PushRequest.serializer())
        val from = call.installation
        badRequestIf(request.ops.size > Api.MAX_OPS_PER_PUSH) { "At most ${Api.MAX_OPS_PER_PUSH} operations per push" }
        // One push comes from one device. Device ids are short, and each user has a bounded number of them, so no
        // member (not even a viewer, whose ops are all refused) can bloat the band's state.
        // Each operation's id is its name in the history (e.g. for undo), so it must be unique in the push.
        badRequestIf(request.ops.map { it.opId }.toSet().size != request.ops.size) { "Operation ids must be unique" }
        val clientIds = request.ops.map { it.clientId }.toSet()
        badRequestIf(clientIds.size > 1) { "All operations of a push must come from one device" }
        clientIds.firstOrNull()?.let { badRequestIf(!it.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid device id" } }
        // Sequence numbers are tracked per user and device, so one user cannot block another user's device.
        // Members only, before any work on their behalf (undo lookups read the log), and a bounded number of undos.
        bands.loadForMember(user, bandId)
        badRequestIf(request.ops.count { it.op is Undo } > Api.MAX_UNDOS_PER_PUSH) { "At most ${Api.MAX_UNDOS_PER_PUSH} undos per push" }
        // An undo restores what the server recorded, not what the client says was there.
        val envelopes = request.ops.map { it.copy(clientId = "${user.userId}/${it.clientId}", op = history.recordedUndo(bandId, it.op)) }
        return retryOnConflict(exhausted = { throw busy("Too many concurrent changes, try again") }) {
            val (band, etag) = bands.loadForMember(user, bandId)
            val device = envelopes.firstOrNull()?.clientId
            val userDevices = band.lastSeq.keys.count { it.startsWith("${user.userId}/") }
            if (device != null && device !in band.lastSeq && userDevices >= Api.MAX_DEVICES_PER_USER) {
                throw ApiError(400, ApiErrors.TOO_MANY_DEVICES, "Too many devices for this user in this band")
            }
            var result = Engine.applyBatch(band, Actor.User(user.userId), envelopes)
            var encoded = if (result.band == band) null else encode(Band.serializer(), result.band)
            // A band must stay loadable: its state goes back in every response, and a Lambda response has 6 MB at most.
            // Too large: the batch again, refusing just the operations that would grow it past the cap,
            // so the rest goes through and the device's queue does not get stuck.
            // Measured in UTF-8 bytes, what the response limit counts: characters undercount other scripts.
            if (encoded != null && encoded.utf8Size() > maxBandBytes) {
                result = Engine.applyBatch(band, Actor.User(user.userId), envelopes) { before, after ->
                    val size = encode(Band.serializer(), after).utf8Size()
                    if (size > maxBandBytes && size > encode(Band.serializer(), before).utf8Size()) {
                        Rejection(RejectionCode.CONFLICT, "The band would be too large; remove old entries or attachments", RejectionReason.LIMIT_REACHED)
                    } else {
                        null
                    }
                }
                encoded = if (result.band == band) null else encode(Band.serializer(), result.band)
            }
            // A precondition failure here: changed meanwhile, the whole batch again on the new state.
            val newEtag = if (encoded == null) etag else store.put(StorageKeys.band(bandId), encoded, ifMatch = etag)
            if (result.accepted.isNotEmpty()) {
                changed.after(user, bandId, band, result.band, envelopes.filter { it.opId in result.accepted }, result.changes, from)
            }
            ok(PushResponse.serializer(), PushResponse(newEtag, result.band, result.accepted, result.rejected, result.duplicates))
        }
    }

    /**
     * The owner asks to delete the band: it is deleted for everyone a week later ([BandDeletions]),
     * unless a leader keeps it meanwhile (`CancelBandDeletion`). Done here rather than as a pushed operation, so the
     * times are the server's; asked again while waiting, nothing changes.
     */
    fun delete(call: Call): HttpResponse = retryOnConflict(exhausted = { throw busy("Too many concurrent changes, try again") }) {
        val user = call.user
        val bandId = call.param("bandId")
        val from = call.installation
        val (band, etag) = bands.loadForMember(user, bandId)
        val me = band.memberOfUser(user.userId)!!
        if (me.role != Role.OWNER) throw ApiError(403, ApiErrors.FORBIDDEN, "Only the owner can delete the band")
        if (band.deletion != null) return@retryOnConflict HttpResponse(204)
        val now = clock()
        val op = ScheduleBandDeletion(BandDeletion(me.id, now, now + BandDeletion.DELAY_MS))
        val opId = "delete-$now"
        val scheduled = when (val result = Engine.apply(band, Actor.System, op, opId, now)) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> throw ApiError(409, ApiErrors.CONFLICT, result.rejection.message)
        }
        store.put(StorageKeys.band(bandId), encode(Band.serializer(), scheduled), ifMatch = etag)
        changed.after(user, bandId, band, scheduled, listOf(OpEnvelope(opId, "server", 0, now, op)), emptyMap(), from)
        HttpResponse(204)
    }

    /**
     * The caller leaves the band (2026-10-27): their account is unlinked from their member, who stays with what they
     * recorded, as when a leader unlinks them. Not the owner: ownership goes to another leader first, or the band is
     * deleted. Done here rather than as a pushed operation, so it either happens on the server or fails for the app to
     * say so, and the member's invites and the user's band list follow at once.
     */
    fun leave(call: Call): HttpResponse = retryOnConflict(exhausted = { throw busy("Too many concurrent changes, try again") }) {
        val user = call.user
        val bandId = call.param("bandId")
        val from = call.installation
        val (band, etag) = bands.loadForMember(user, bandId)
        val me = band.memberOfUser(user.userId)!!
        if (me.role == Role.OWNER) throw ApiError(409, ApiErrors.OWNER_CANNOT_LEAVE, "Hand the band to another leader or delete it first")
        val op = UnlinkUser(me.id)
        val opId = "leave-${clock()}"
        val left = when (val result = Engine.apply(band, Actor.System, op, opId, clock())) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> throw ApiError(409, ApiErrors.CONFLICT, result.rejection.message)
        }
        // The commit point: what follows is tidying up, which a failure must not report as a failed leave.
        store.put(StorageKeys.band(bandId), encode(Band.serializer(), left), ifMatch = etag)
        changed.after(user, bandId, band, left, listOf(OpEnvelope(opId, "server", 0, clock(), op)), emptyMap(), from)
        HttpResponse(204)
    }

    /** The band's file exactly as stored, for leaders: putting it back changes nothing. */
    fun raw(call: Call): HttpResponse {
        val user = call.user
        val bandId = call.param("bandId")
        bands.loadForLeader(user, bandId, "Only band leaders see the raw data")
        val blob = store.get(StorageKeys.band(bandId)) ?: throw bands.gone(user, bandId)
        return HttpResponse(200, blob.content, mapOf("content-type" to "application/json; charset=utf-8"))
    }
}
