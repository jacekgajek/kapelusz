package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.withPicture
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.Rejection
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser

/** A band's state: creating, reading, changing it by operations (push), and deleting it. */
internal class BandHandlers(
    private val store: BlobStore,
    private val clock: () -> Long,
    private val maxBandBytes: Int,
    private val bands: BandAccess,
    private val users: UserHandlers,
    private val history: HistoryHandlers,
    private val invites: InviteHandlers,
    private val attachments: AttachmentHandlers,
    private val linkPages: LinkPages,
) {
    fun create(user: VerifiedUser, request: CreateBandRequest): HttpResponse {
        badRequestIf(request.name.isBlank() || request.ownerName.isBlank()) { "Band and member names are required" }
        // The same limits as every change (ITERATION_3 R1): ids that become storage keys, names of sensible length.
        StorageKeys.safe(request.bandId)
        users.requireRoomForBand(user.userId, request.bandId)
        val draft = Band.create(request.bandId, request.name.trim(), request.currency, request.ownerMemberId, request.ownerName.trim(), user.userId)
        Limits.violation(draft.copy(name = "", members = emptyList()), draft)?.let { throw ApiError(400, ApiErrors.BAD_REQUEST, it.message) }
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

    fun state(user: VerifiedUser, bandId: String, ifNoneMatch: String?): HttpResponse {
        val (band, etag) = bands.loadForMember(user, bandId)
        if (ifNoneMatch == etag) return HttpResponse(304, headers = mapOf("etag" to etag))
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band), mapOf("etag" to etag))
    }

    fun push(user: VerifiedUser, bandId: String, request: PushRequest): HttpResponse {
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
        // An undo restores what the server recorded, not what the client says was there (ITERATION_2 §7.2).
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
            // Too large: the batch again, refusing just the operations that would grow it past the cap (ITERATION_3 R1),
            // so the rest goes through and the device's queue does not get stuck.
            // Measured in UTF-8 bytes, what the response limit counts (V5-M1 A2): characters undercount other scripts.
            if (encoded != null && encoded.utf8Size() > maxBandBytes) {
                result = Engine.applyBatch(band, Actor.User(user.userId), envelopes) { before, after ->
                    val size = encode(Band.serializer(), after).utf8Size()
                    if (size > maxBandBytes && size > encode(Band.serializer(), before).utf8Size()) {
                        Rejection(RejectionCode.CONFLICT, "The band would be too large; remove old entries or attachments", RejectionReason.LIMIT_REACHED)
                    } else null
                }
                encoded = if (result.band == band) null else encode(Band.serializer(), result.band)
            }
            // A precondition failure here: changed meanwhile, the whole batch again on the new state.
            val newEtag = if (encoded == null) etag else store.put(StorageKeys.band(bandId), encoded, ifMatch = etag)
            if (result.accepted.isNotEmpty()) {
                // A member whose account was linked or unlinked must not keep any invite (see InviteHandlers.revokeFor).
                envelopes.filter { it.opId in result.accepted }.map { it.op }
                    .mapNotNull { (it as? UnlinkUser)?.memberId }
                    .forEach { memberId -> runCatching { invites.revokeFor(bandId, memberId) } }
                // New events (also one brought back by an undo): where their links find them.
                linkPages.pointTo(bandId, result.band.events.map { it.id } - band.events.map { it.id }.toSet())
                // Files of attachments that are gone (removed, or what they belonged to deleted); versioning keeps them for 30 days.
                runCatching { attachments.deleteRemoved(bandId, band, result.band) }
                    .onFailure { System.err.println("Could not delete attachment files of band $bandId: $it") }
                // The change is already saved; a failed log write must not make the client retry it.
                runCatching { history.append(user, band, bandId, envelopes.filter { it.opId in result.accepted }, result.changes) }
                    .onFailure { System.err.println("Could not append history for band $bandId: $it") }
            }
            ok(PushResponse.serializer(), PushResponse(newEtag, result.band, result.accepted, result.rejected, result.duplicates))
        }
    }

    /**
     * Deletes a band for everyone (owner only): its state first, so every member's next request finds it gone, then the
     * members' band lists, invites, history and attachment files. A small marker tells former members that it was
     * deleted. Bucket versioning keeps the objects for 30 days, so a mistake can still be undone by hand.
     */
    fun delete(user: VerifiedUser, bandId: String): HttpResponse {
        val (band, _) = bands.loadForMember(user, bandId)
        val me = band.memberOfUser(user.userId)!!
        if (me.role != Role.OWNER) throw ApiError(403, ApiErrors.FORBIDDEN, "Only the owner can delete the band")
        val userIds = band.members.mapNotNull { it.userId }
        store.put(StorageKeys.deleted(bandId), encode(DeletedBand.serializer(), DeletedBand(band.name, me.name, clock(), userIds)))
        store.delete(StorageKeys.band(bandId))
        userIds.forEach { runCatching { users.removeBandFromUser(it, bandId) } }
        invites.loadCodes(bandId).forEach { code -> runCatching { store.delete(StorageKeys.invite(code)) } }
        store.deleteAll(store.list(StorageKeys.bandPrefix(bandId)).filter { it != StorageKeys.deleted(bandId) })
        runCatching { history.deleteBand(bandId) }.onFailure { System.err.println("Could not delete the history of band $bandId: $it") }
        return HttpResponse(204)
    }

    /** The band's file exactly as stored, for leaders (ITERATION_2 §7.1): putting it back changes nothing. */
    fun raw(user: VerifiedUser, bandId: String): HttpResponse {
        bands.loadForLeader(user, bandId, "Only band leaders see the raw data")
        val blob = store.get(StorageKeys.band(bandId)) ?: throw bands.gone(user, bandId)
        return HttpResponse(200, blob.content, mapOf("content-type" to "application/json; charset=utf-8"))
    }
}
