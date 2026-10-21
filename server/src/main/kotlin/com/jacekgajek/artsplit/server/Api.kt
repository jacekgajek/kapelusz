package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.api.ApiErrors
import com.jacekgajek.artsplit.api.AttachmentUploadRequest
import com.jacekgajek.artsplit.api.AttachmentUploadResponse
import com.jacekgajek.artsplit.api.AttachmentUrlResponse
import com.jacekgajek.artsplit.model.Attachment
import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.CalendarSubscription
import com.jacekgajek.artsplit.api.CalendarSubscriptionChange
import com.jacekgajek.artsplit.api.BandSummary
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.ErrorResponse
import com.jacekgajek.artsplit.api.CreateInviteRequest
import com.jacekgajek.artsplit.api.HistoryEntry
import com.jacekgajek.artsplit.api.HistoryResponse
import com.jacekgajek.artsplit.api.InviteInfo
import com.jacekgajek.artsplit.api.InvitePreview
import com.jacekgajek.artsplit.api.InvitesResponse
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.UnlinkUser
import java.security.SecureRandom
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.api.SessionResponse
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.OpEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The user's band list, stored as `users/{googleSub}.json`. */
@Serializable
data class UserRecord(val userId: String, val bandIds: List<String> = emptyList())

/** Invite code lookups in the current hour, stored as `users/{googleSub}-join-attempts.json`. */
@Serializable
data class JoinAttempts(val windowStart: Long, val failures: Int)

/** Active invite codes of a band, stored as `bands/{id}/invites.json` (codes are only visible to admins). */
@Serializable
data class InviteCodes(val codes: List<String>)

/** What is left of a deleted band, `bands/{id}/deleted.json`: who may learn that it was deleted, and by whom. */
@Serializable
data class DeletedBand(val name: String, val deletedBy: String, val deletedAt: Long, val userIds: List<String>)

/** A user's calendar feed, `users/{sub}-calendar.json`. */
@Serializable
data class StoredCalendar(val secret: String, val includeAll: Boolean = false)

/** Whose feed a secret opens, `calendar/{secret}.json`. */
@Serializable
data class CalendarOwner(val userId: String)

/** An invite as stored in `invites/{code}.json`. */
@Serializable
data class StoredInvite(val info: InviteInfo, val createdBy: String)

private class ApiError(val status: Int, val code: String, message: String) : RuntimeException(message)

/**
 * The HTTP API (VERSION_1 §5.3). Every operation goes through the shared [Engine], i.e. the same permission
 * checks and reducer the apps run, so clients cannot bypass the role rules.
 */
class Api(
    private val store: BlobStore,
    private val verifier: TokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessions: SessionTokens = SessionTokens(store, clock),
    /** No minimum by default (tests); the Lambda handler passes [MIN_APP_VERSION]. */
    private val minAppVersion: Int = 0,
) {
    fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (e: ApiError) {
        error(e.status, e.code, e.message.orEmpty())
    } catch (e: SerializationException) {
        error(400, "bad_request", e.message.orEmpty())
    }

    private fun route(request: HttpRequest): HttpResponse {
        // CORS preflight: the catch-all API Gateway route forwards it here. Browsers send it without credentials and
        // need a 2xx answer; API Gateway adds the CORS headers.
        if (request.method == "OPTIONS") return HttpResponse(204)
        // The calendar feed is fetched by calendar apps: no sign-in, no app version; the secret in the path is the key.
        val calendarFeed = Regex("^/calendar/([A-Za-z0-9_-]{20,64})\\.ics$").find(request.path)
        if (request.method == "GET" && calendarFeed != null) return calendarFeed(calendarFeed.groupValues[1])
        if (!appSupported(request.query[ApiErrors.APP_VERSION_PARAMETER])) {
            throw ApiError(426, ApiErrors.UPDATE_REQUIRED, "This version of Art Split is too old; update it")
        }
        val user = authenticate(request)
        val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            request.method == "GET" && segments == listOf("me") -> me(user)
            request.method == "GET" && segments == listOf("me", "calendar") -> calendarSubscription(user, request, CalendarSubscriptionChange())
            request.method == "POST" && segments == listOf("me", "calendar") ->
                calendarSubscription(user, request, parse(request, CalendarSubscriptionChange.serializer()))
            // A new session for a Google ID token, or a renewed one for a current session (sliding expiry).
            request.method == "POST" && segments == listOf("session") -> {
                val (token, expiresAt) = sessions.issue(user)
                ok(SessionResponse.serializer(), SessionResponse(token, expiresAt))
            }
            request.method == "POST" && segments == listOf("bands") -> createBand(user, parse(request, CreateBandRequest.serializer()))
            request.method == "GET" && segments.size == 3 && segments[0] == "bands" && segments[2] == "state" ->
                state(user, segments[1], request.header("if-none-match"))
            request.method == "POST" && segments.size == 3 && segments[0] == "bands" && segments[2] == "ops" ->
                push(user, segments[1], parse(request, PushRequest.serializer()))
            request.method == "DELETE" && segments.size == 2 && segments[0] == "bands" -> deleteBand(user, segments[1])
            request.method == "GET" && segments.size == 3 && segments[0] == "bands" && segments[2] == "log" ->
                history(user, segments[1], request.query["month"], request.query["before"])
            request.method == "GET" && segments.size == 3 && segments[0] == "bands" && segments[2] == "invites" ->
                listInvites(user, segments[1])
            request.method == "POST" && segments.size == 3 && segments[0] == "bands" && segments[2] == "invites" ->
                createInvite(user, segments[1], parse(request, CreateInviteRequest.serializer()))
            request.method == "DELETE" && segments.size == 4 && segments[0] == "bands" && segments[2] == "invites" ->
                revokeInvite(user, segments[1], segments[3])
            request.method == "POST" && segments.size == 3 && segments[0] == "bands" && segments[2] == "attachments" ->
                uploadAttachment(user, segments[1], parse(request, AttachmentUploadRequest.serializer()))
            request.method == "GET" && segments.size == 4 && segments[0] == "bands" && segments[2] == "attachments" ->
                downloadAttachment(user, segments[1], segments[3])
            request.method == "GET" && segments.size == 2 && segments[0] == "invites" -> previewInvite(user, segments[1])
            request.method == "POST" && segments.size == 3 && segments[0] == "invites" && segments[2] == "accept" ->
                acceptInvite(user, segments[1])
            else -> throw ApiError(404, "not_found", "No route for ${request.method} ${request.path}")
        }
    }

    /**
     * Apps send their version code; a version below [minAppVersion] must update first. Apps from before version 2 send
     * none, and local development builds send 0 (always allowed, so they can be tested against the live API).
     */
    private fun appSupported(version: String?): Boolean {
        if (minAppVersion <= 0) return true
        val code = version?.toIntOrNull() ?: return false
        return code == 0 || code >= minAppVersion
    }

    private fun authenticate(request: HttpRequest): VerifiedUser {
        val token = request.header("authorization")?.removePrefix("Bearer ")?.trim()
            ?: throw ApiError(401, "unauthorized", "Missing bearer token")
        // Art Split sessions are recognised by their prefix; anything else must be a Google ID token.
        val user = if (token.startsWith(SessionTokens.PREFIX)) sessions.verify(token) else verifier.verify(token)
        return user ?: throw ApiError(401, "unauthorized", "Invalid or expired token")
    }

    // ---- endpoints ----

    private fun me(user: VerifiedUser): HttpResponse {
        val record = loadUser(user.userId)
        val bands = record.bandIds.mapNotNull { id ->
            val band = loadBand(id)?.first ?: return@mapNotNull null
            val member = band.memberOfUser(user.userId) ?: return@mapNotNull null
            BandSummary(band.id, band.name, member.name, member.role)
        }
        return ok(MeResponse.serializer(), MeResponse(user.userId, user.email, user.name, bands))
    }

    private fun createBand(user: VerifiedUser, request: CreateBandRequest): HttpResponse {
        badRequestIf(request.name.isBlank() || request.ownerName.isBlank()) { "Band and member names are required" }
        badRequestIf(!request.currency.matches(Regex("[A-Z]{3}"))) { "Currency must be an ISO code" }
        val band = Band.create(request.bandId, request.name.trim(), request.currency, request.ownerMemberId, request.ownerName.trim(), user.userId)
        val etag = try {
            store.put(bandKey(band.id), encode(Band.serializer(), band), ifNoneMatch = true)
        } catch (e: PreconditionFailed) {
            // A retried create of the caller's own band is fine; anything else is a conflict.
            val (existing, existingEtag) = loadBand(band.id) ?: throw ApiError(409, "conflict", "Band id is taken")
            if (existing.owner?.userId != user.userId) throw ApiError(409, "conflict", "Band id is taken")
            addBandToUser(user.userId, band.id)
            return ok(BandStateResponse.serializer(), BandStateResponse(existingEtag, existing))
        }
        addBandToUser(user.userId, band.id)
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band))
    }

    private fun state(user: VerifiedUser, bandId: String, ifNoneMatch: String?): HttpResponse {
        val (band, etag) = loadBandForMember(user, bandId)
        if (ifNoneMatch == etag) return HttpResponse(304, headers = mapOf("etag" to etag))
        return ok(BandStateResponse.serializer(), BandStateResponse(etag, band), mapOf("etag" to etag))
    }

    private fun push(user: VerifiedUser, bandId: String, request: PushRequest): HttpResponse {
        badRequestIf(request.ops.size > MAX_OPS_PER_PUSH) { "At most $MAX_OPS_PER_PUSH operations per push" }
        // One push comes from one device. Device ids are short, and each user has a bounded number of them, so no
        // member (not even a viewer, whose ops are all refused) can bloat the band's state.
        val clientIds = request.ops.map { it.clientId }.toSet()
        badRequestIf(clientIds.size > 1) { "All operations of a push must come from one device" }
        clientIds.firstOrNull()?.let { badRequestIf(!it.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid device id" } }
        // Sequence numbers are tracked per user and device, so one user cannot block another user's device.
        val envelopes = request.ops.map { it.copy(clientId = "${user.userId}/${it.clientId}") }
        repeat(MAX_ATTEMPTS) {
            val (band, etag) = loadBandForMember(user, bandId)
            val device = envelopes.firstOrNull()?.clientId
            val userDevices = band.lastSeq.keys.count { it.startsWith("${user.userId}/") }
            if (device != null && device !in band.lastSeq && userDevices >= MAX_DEVICES_PER_USER) {
                throw ApiError(400, "too_many_devices", "Too many devices for this user in this band")
            }
            val result = Engine.applyBatch(band, Actor.User(user.userId), envelopes)
            val newEtag = try {
                if (result.band == band) etag else store.put(bandKey(bandId), encode(Band.serializer(), result.band), ifMatch = etag)
            } catch (e: PreconditionFailed) {
                return@repeat
            }
            if (result.accepted.isNotEmpty()) {
                // A member whose account was linked or unlinked must not keep any invite (see revokeInvitesFor).
                envelopes.filter { it.opId in result.accepted }.map { it.op }
                    .mapNotNull { (it as? UnlinkUser)?.memberId }
                    .forEach { memberId -> runCatching { revokeInvitesFor(bandId, memberId) } }
                // Files of attachments that are gone (removed, or their cost deleted); versioning keeps them for 30 days.
                runCatching { deleteRemovedAttachments(bandId, band, result.band) }
                    .onFailure { System.err.println("Could not delete attachment files of band $bandId: $it") }
                // The change is already saved; a failed log write must not make the client retry it.
                runCatching { appendLog(user, band, bandId, envelopes.filter { it.opId in result.accepted }) }
                    .onFailure { System.err.println("Could not append history for band $bandId: $it") }
            }
            return ok(
                PushResponse.serializer(),
                PushResponse(newEtag, result.band, result.accepted, result.rejected, result.duplicates),
            )
        }
        throw ApiError(409, "busy", "Too many concurrent changes, try again")
    }

    /**
     * One page of a month's history, newest first. [before] is the `next` cursor of the previous page; listing keys is
     * cheap, only the page's entries are fetched, so a busy month never times out.
     */
    private fun history(user: VerifiedUser, bandId: String, month: String?, before: String?): HttpResponse {
        loadBandForMember(user, bandId)
        val selected = month ?: monthOf(clock())
        badRequestIf(!selected.matches(Regex("\\d{4}-\\d{2}"))) { "month must be yyyy-MM" }
        val prefix = "bands/$bandId/log/$selected/"
        val keys = store.list(prefix).asReversed().let { all -> if (before == null) all else all.dropWhile { it >= prefix + before } }
        val page = keys.take(HISTORY_PAGE)
        val entries = page.mapNotNull { key -> store.get(key)?.let { decode(HistoryEntry.serializer(), it.content) } }
        val next = if (keys.size > page.size) page.last().removePrefix(prefix) else null
        return ok(HistoryResponse.serializer(), HistoryResponse(selected, entries, next))
    }

    /**
     * Deletes a band for everyone (owner only): its state first, so every member's next request finds it gone, then the
     * members' band lists, invites, history and attachment files. A small marker tells former members that it was
     * deleted. Bucket versioning keeps the objects for 30 days, so a mistake can still be undone by hand.
     */
    private fun deleteBand(user: VerifiedUser, bandId: String): HttpResponse {
        val (band, _) = loadBandForMember(user, bandId)
        val me = band.memberOfUser(user.userId)!!
        if (me.role != Role.OWNER) throw ApiError(403, "forbidden", "Only the owner can delete the band")
        val userIds = band.members.mapNotNull { it.userId }
        store.put(deletedKey(bandId), encode(DeletedBand.serializer(), DeletedBand(band.name, me.name, clock(), userIds)))
        store.delete(bandKey(bandId))
        userIds.forEach { runCatching { removeBandFromUser(it, bandId) } }
        loadInviteCodes(bandId).forEach { code -> runCatching { store.delete(inviteKey(code)) } }
        store.deleteAll(store.list("bands/$bandId/").filter { it != deletedKey(bandId) })
        return HttpResponse(204)
    }

    private fun removeBandFromUser(userId: String, bandId: String) {
        repeat(MAX_ATTEMPTS) {
            val blob = store.get(userKey(userId)) ?: return
            val record = decode(UserRecord.serializer(), blob.content)
            if (bandId !in record.bandIds) return
            try {
                store.put(userKey(userId), encode(UserRecord.serializer(), record.copy(bandIds = record.bandIds - bandId)), ifMatch = blob.etag)
                return
            } catch (e: PreconditionFailed) {
                // retry with the fresh record
            }
        }
    }

    // ---- calendar subscription ----

    /** The user's feed link, created on first use; [change] can switch "include all" or make a new link. */
    private fun calendarSubscription(user: VerifiedUser, request: HttpRequest, change: CalendarSubscriptionChange): HttpResponse {
        repeat(MAX_ATTEMPTS) {
            val blob = store.get(calendarKey(user.userId))
            val current = blob?.let { decode(StoredCalendar.serializer(), it.content) }
            val secret = if (current == null || change.reset) newCalendarSecret() else current.secret
            val updated = StoredCalendar(secret, change.includeAll ?: current?.includeAll ?: false)
            if (updated != current) {
                try {
                    store.put(calendarKey(user.userId), encode(StoredCalendar.serializer(), updated), ifMatch = blob?.etag, ifNoneMatch = blob == null)
                } catch (e: PreconditionFailed) {
                    return@repeat
                }
                if (secret != current?.secret) {
                    store.put(calendarSecretKey(secret), encode(CalendarOwner.serializer(), CalendarOwner(user.userId)))
                    current?.let { store.delete(calendarSecretKey(it.secret)) }
                }
            }
            // The link points at this API, whichever address the app used.
            val host = request.header("host") ?: throw ApiError(400, "bad_request", "No host")
            return ok(CalendarSubscription.serializer(), CalendarSubscription("https://$host/calendar/$secret.ics", updated.includeAll))
        }
        throw ApiError(409, "busy", "Try again")
    }

    private fun calendarFeed(secret: String): HttpResponse {
        val owner = store.get(calendarSecretKey(secret))?.let { decode(CalendarOwner.serializer(), it.content) }
            ?: return error(404, "not_found", "No such calendar")
        val settings = store.get(calendarKey(owner.userId))?.let { decode(StoredCalendar.serializer(), it.content) }
        if (settings?.secret != secret) return error(404, "not_found", "No such calendar")
        val bands = loadUser(owner.userId).bandIds.mapNotNull { loadBand(it)?.first }
        val now = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDateTime()
        val stamp = kotlinx.datetime.LocalDateTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
        val body = CalendarFeed.build(bands, owner.userId, settings.includeAll, stamp.date, WEB_URL, stamp)
        return HttpResponse(200, body, mapOf("content-type" to "text/calendar; charset=utf-8", "cache-control" to "private, max-age=900"))
    }

    private fun newCalendarSecret(): String = ByteArray(24).also { random.nextBytes(it) }.let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    // ---- attachments ----

    /**
     * A URL to upload the file of an attachment already listed on its cost. Any member who can add costs may upload
     * (the attachment op itself was permission-checked); the URL can only create the file, never replace it, and is
     * signed for exactly the declared size.
     */
    private fun uploadAttachment(user: VerifiedUser, bandId: String, request: AttachmentUploadRequest): HttpResponse {
        val (band, _) = loadBandForMember(user, bandId)
        if (!band.memberOfUser(user.userId)!!.role.atLeast(Role.MEMBER)) throw ApiError(403, "forbidden", "Viewers cannot upload")
        val attachment = band.cost(request.costId)?.attachments?.firstOrNull { it.id == request.attachmentId }
            ?: throw ApiError(404, ApiErrors.ATTACHMENT_NOT_FOUND, "No such attachment")
        val key = attachmentKey(bandId, attachment)
        if (store.exists(key)) throw ApiError(409, ApiErrors.ATTACHMENT_EXISTS, "Already uploaded")
        val presigned = store.presignPut(key, attachment.kind.contentType, attachment.sizeBytes ?: throw ApiError(400, "bad_request", "No size"))
        return ok(AttachmentUploadResponse.serializer(), AttachmentUploadResponse(presigned.url, presigned.headers))
    }

    /** A URL to open an attachment; anyone who sees the cost (every member) may. */
    private fun downloadAttachment(user: VerifiedUser, bandId: String, attachmentId: String): HttpResponse {
        val (band, _) = loadBandForMember(user, bandId)
        val attachment = band.costs.firstNotNullOfOrNull { cost -> cost.attachments.firstOrNull { it.id == attachmentId } }
            ?: throw ApiError(404, ApiErrors.ATTACHMENT_NOT_FOUND, "No such attachment")
        val key = attachmentKey(bandId, attachment)
        if (!store.exists(key)) throw ApiError(404, ApiErrors.ATTACHMENT_NOT_UPLOADED, "Not uploaded yet")
        val fileName = attachment.fileName ?: "${attachment.id}.${attachment.kind.extension}"
        return ok(AttachmentUrlResponse.serializer(), AttachmentUrlResponse(store.presignGet(key, attachment.kind.contentType, fileName)))
    }

    private fun deleteRemovedAttachments(bandId: String, before: Band, after: Band) {
        val kept = after.costs.flatMap { it.attachments }.map { it.id }.toSet()
        before.costs.flatMap { it.attachments }.filter { it.id !in kept }.forEach { store.delete(attachmentKey(bandId, it)) }
    }

    // ---- invites ----

    private fun loadBandForAdmin(user: VerifiedUser, bandId: String): Pair<Band, String> {
        val loaded = loadBandForMember(user, bandId)
        val me = loaded.first.memberOfUser(user.userId)!!
        if (!me.role.atLeast(Role.ADMIN)) throw ApiError(403, "forbidden", "Only band admins manage invites")
        return loaded
    }

    private fun listInvites(user: VerifiedUser, bandId: String): HttpResponse {
        loadBandForAdmin(user, bandId)
        val active = loadInviteCodes(bandId).mapNotNull { loadInvite(it)?.info }.filter { it.expiresAt > clock() }
        return ok(InvitesResponse.serializer(), InvitesResponse(active))
    }

    private fun createInvite(user: VerifiedUser, bandId: String, request: CreateInviteRequest): HttpResponse {
        val (band, _) = loadBandForAdmin(user, bandId)
        val member = band.member(request.memberId) ?: throw ApiError(404, "not_found", "No such member")
        badRequestIf(member.userId != null) { "This member is already linked to an account" }
        badRequestIf(member.archived) { "This member is archived" }
        badRequestIf(request.role == Role.OWNER) { "Invites cannot grant ownership" }
        // A new code for the same member replaces the old one.
        loadInviteCodes(bandId).forEach { code -> if (loadInvite(code)?.info?.memberId == member.id) deleteInvite(bandId, code) }
        repeat(MAX_ATTEMPTS) {
            val info = InviteInfo(newInviteCode(), bandId, member.id, request.role, clock() + INVITE_LIFETIME_MS)
            try {
                store.put(inviteKey(info.code), encode(StoredInvite.serializer(), StoredInvite(info, user.userId)), ifNoneMatch = true)
            } catch (e: PreconditionFailed) {
                return@repeat // code collision: draw another one
            }
            updateInviteCodes(bandId) { it + info.code }
            return ok(InviteInfo.serializer(), info)
        }
        throw ApiError(409, "busy", "Could not create an invite code")
    }

    private fun revokeInvite(user: VerifiedUser, bandId: String, code: String): HttpResponse {
        loadBandForAdmin(user, bandId)
        if (loadInvite(code)?.info?.bandId == bandId) deleteInvite(bandId, code)
        return HttpResponse(204)
    }

    private fun previewInvite(user: VerifiedUser, code: String): HttpResponse {
        val invite = findInvite(user, code).info
        val band = loadBand(invite.bandId)?.first ?: throw inviteNotFound()
        val member = band.member(invite.memberId) ?: throw inviteNotFound()
        return ok(InvitePreview.serializer(), InvitePreview(invite.code, band.id, band.name, member.name, invite.role, invite.expiresAt))
    }

    private fun acceptInvite(user: VerifiedUser, code: String): HttpResponse {
        val stored = findInvite(user, code)
        val invite = stored.info
        repeat(MAX_ATTEMPTS) {
            val (band, etag) = loadBand(invite.bandId) ?: throw inviteNotFound()
            band.memberOfUser(user.userId)?.let { existing ->
                if (existing.id != invite.memberId) throw ApiError(409, ApiErrors.ALREADY_MEMBER, "You are already a member of this band")
                // A retry after an interrupted accept: the link is done, finish what may not have been.
                finishJoin(user, band.id, invite.memberId)
                return ok(BandStateResponse.serializer(), BandStateResponse(etag, band))
            }
            // The invite only stands while its issuer is still an active admin and the member is still in the band.
            val issuer = band.memberOfUser(stored.createdBy)
            val member = band.member(invite.memberId)
            if (issuer == null || issuer.archived || !issuer.role.atLeast(Role.ADMIN) || member == null || member.archived) {
                deleteInvite(band.id, invite.code)
                throw inviteNotFound()
            }
            val linked = when (val result = Engine.apply(band, Actor.System, LinkUser(invite.memberId, user.userId, invite.role), "invite-${invite.code}", clock())) {
                is ApplyResult.Applied -> result.band
                // The member was linked meanwhile.
                is ApplyResult.Rejected -> throw inviteNotFound()
            }
            val newEtag = try {
                store.put(bandKey(band.id), encode(Band.serializer(), linked), ifMatch = etag)
            } catch (e: PreconditionFailed) {
                return@repeat
            }
            finishJoin(user, band.id, invite.memberId)
            runCatching { appendLog(user, linked, band.id, listOf(OpEnvelope("invite-${invite.code}", "server", 0, clock(), LinkUser(invite.memberId, user.userId, invite.role)))) }
            return ok(BandStateResponse.serializer(), BandStateResponse(newEtag, linked))
        }
        throw ApiError(409, "busy", "Too many concurrent changes, try again")
    }

    /** After a join: list the band for the user and cancel every invite for the member (both idempotent). */
    private fun finishJoin(user: VerifiedUser, bandId: String, memberId: String) {
        addBandToUser(user.userId, bandId)
        revokeInvitesFor(bandId, memberId)
    }

    /**
     * Cancels all invites for a member. Called whenever the member's account link changes, so no leftover code (from a
     * race or an interrupted request) can link someone after an admin unlinked them.
     */
    private fun revokeInvitesFor(bandId: String, memberId: String) {
        loadInviteCodes(bandId).forEach { code ->
            val invite = loadInvite(code)
            if (invite == null || invite.info.memberId == memberId) deleteInvite(bandId, code)
        }
    }

    /**
     * Looks up an active invite. Every lookup first reserves one of the user's [MAX_JOIN_ATTEMPTS] attempts per hour
     * with a conditional write, so parallel requests cannot get around the limit. A real join needs two (preview, accept).
     */
    private fun findInvite(user: VerifiedUser, rawCode: String): StoredInvite {
        reserveJoinAttempt(user.userId)
        val code = rawCode.uppercase()
        return code.takeIf { it.matches(Regex("[$INVITE_ALPHABET]{$INVITE_LENGTH}")) }?.let { loadInvite(it) }
            ?.takeIf { it.info.expiresAt > clock() }
            ?: throw inviteNotFound()
    }

    private fun reserveJoinAttempt(userId: String) {
        val key = attemptsKey(userId)
        repeat(MAX_ATTEMPTS) {
            val blob = store.get(key)
            val current = blob?.let { decode(JoinAttempts.serializer(), it.content) }?.takeIf { clock() - it.windowStart < ATTEMPT_WINDOW_MS }
            if (current != null && current.failures >= MAX_JOIN_ATTEMPTS) {
                throw ApiError(429, ApiErrors.TOO_MANY_ATTEMPTS, "Too many attempts, try again later")
            }
            val updated = current?.copy(failures = current.failures + 1) ?: JoinAttempts(clock(), 1)
            try {
                store.put(key, encode(JoinAttempts.serializer(), updated), ifMatch = blob?.etag, ifNoneMatch = blob == null)
                return
            } catch (e: PreconditionFailed) {
                // a parallel request counted first: read again
            }
        }
        throw ApiError(429, ApiErrors.TOO_MANY_ATTEMPTS, "Too many attempts, try again later")
    }

    private fun inviteNotFound() = ApiError(404, ApiErrors.INVITE_NOT_FOUND, "No such invite")

    private fun loadInvite(code: String): StoredInvite? =
        store.get(inviteKey(code))?.let { decode(StoredInvite.serializer(), it.content) }

    private fun loadInviteCodes(bandId: String): List<String> =
        store.get(bandInvitesKey(bandId))?.let { decode(InviteCodes.serializer(), it.content).codes } ?: emptyList()

    private fun updateInviteCodes(bandId: String, change: (List<String>) -> List<String>) {
        repeat(MAX_ATTEMPTS) {
            val blob = store.get(bandInvitesKey(bandId))
            val codes = blob?.let { decode(InviteCodes.serializer(), it.content).codes } ?: emptyList()
            try {
                store.put(bandInvitesKey(bandId), encode(InviteCodes.serializer(), InviteCodes(change(codes))), ifMatch = blob?.etag, ifNoneMatch = blob == null)
                return
            } catch (e: PreconditionFailed) {
                // retry with the fresh list
            }
        }
        throw ApiError(409, "busy", "Could not update invites")
    }

    private fun deleteInvite(bandId: String, code: String) {
        store.delete(inviteKey(code))
        updateInviteCodes(bandId) { codes -> codes - code }
    }

    private fun newInviteCode(): String = (1..INVITE_LENGTH).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")

    // ---- storage ----

    private fun loadBand(bandId: String): Pair<Band, String>? =
        store.get(bandKey(bandId))?.let { decode(Band.serializer(), it.content) to it.etag }

    private fun loadBandForMember(user: VerifiedUser, bandId: String): Pair<Band, String> {
        val loaded = loadBand(bandId) ?: throw bandGone(user, bandId)
        val member = loaded.first.memberOfUser(user.userId)
        // Same answer for "no such band" and "not your band", so band ids cannot be probed. A distinct code (not the
        // generic route 404) lets clients drop the band only when the band itself is gone for them.
        if (member == null || member.archived) throw ApiError(404, BAND_NOT_FOUND, "No such band")
        return loaded
    }

    /** "Deleted" for the band's former members, the usual "not found" for everyone else (no probing of ids). */
    private fun bandGone(user: VerifiedUser, bandId: String): ApiError {
        val deleted = store.get(deletedKey(bandId))?.let { decode(DeletedBand.serializer(), it.content) }
        return if (deleted != null && user.userId in deleted.userIds) {
            ApiError(404, ApiErrors.BAND_DELETED, "${deleted.name} was deleted by ${deleted.deletedBy}")
        } else {
            ApiError(404, BAND_NOT_FOUND, "No such band")
        }
    }

    private fun loadUser(userId: String): UserRecord =
        store.get(userKey(userId))?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)

    private fun addBandToUser(userId: String, bandId: String) {
        repeat(MAX_ATTEMPTS) {
            val blob = store.get(userKey(userId))
            val record = blob?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)
            if (bandId in record.bandIds) return
            val updated = encode(UserRecord.serializer(), record.copy(bandIds = record.bandIds + bandId))
            try {
                store.put(userKey(userId), updated, ifMatch = blob?.etag, ifNoneMatch = blob == null)
                return
            } catch (e: PreconditionFailed) {
                // retry with the fresh record
            }
        }
        throw ApiError(409, "busy", "Could not update the user record")
    }

    private fun appendLog(user: VerifiedUser, band: Band, bandId: String, ops: List<OpEnvelope>) {
        val now = clock()
        val month = monthOf(now)
        val entry = HistoryEntry(user.userId, band.memberOfUser(user.userId)?.id, now, ops)
        store.put("bands/$bandId/log/$month/$now-${UUID.randomUUID()}.json", encode(HistoryEntry.serializer(), entry), ifNoneMatch = true)
    }

    // ---- helpers ----

    private fun monthOf(millis: Long) = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis))

    private fun <T> parse(request: HttpRequest, serializer: KSerializer<T>): T =
        decode(serializer, request.body ?: throw ApiError(400, "bad_request", "Missing body"))

    private fun <T> ok(serializer: KSerializer<T>, value: T, headers: Map<String, String> = emptyMap()) =
        HttpResponse(200, encode(serializer, value), headers + JSON)

    private fun error(status: Int, code: String, message: String) =
        HttpResponse(status, encode(ErrorResponse.serializer(), ErrorResponse(code, message)), JSON)

    companion object {
        /**
         * The oldest app version (deploy run number) the server still serves; older apps show "update required".
         *
         * Raise it in two steps, never in the change that needs it: first deploy apps that handle the new data, then
         * set this to **that deploy's run number** (already live, so its web app and APK exist). Setting it to a
         * number not yet deployed would refuse every app, and during a deploy the server switches before the new web
         * app and APK are uploaded.
         */
        const val MIN_APP_VERSION = 11

        const val MAX_ATTEMPTS = 5
        const val MAX_OPS_PER_PUSH = 500
        const val MAX_DEVICES_PER_USER = 50
        const val HISTORY_PAGE = 100
        const val INVITE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val INVITE_LENGTH = 6
        const val INVITE_LIFETIME_MS = 72 * 3600 * 1000L
        const val MAX_JOIN_ATTEMPTS = 10
        const val ATTEMPT_WINDOW_MS = 3600 * 1000L
        private val random = SecureRandom()
        const val BAND_NOT_FOUND = ApiErrors.BAND_NOT_FOUND
        private val JSON = mapOf("content-type" to "application/json")

        fun bandKey(bandId: String) = "bands/${safe(bandId)}/state.json"
        fun userKey(userId: String) = "users/${safe(userId)}.json"
        fun attemptsKey(userId: String) = "users/${safe(userId)}-join-attempts.json"
        fun inviteKey(code: String) = "invites/$code.json"
        fun bandInvitesKey(bandId: String) = "bands/${safe(bandId)}/invites.json"
        fun deletedKey(bandId: String) = "bands/${safe(bandId)}/deleted.json"
        fun calendarKey(userId: String) = "users/${safe(userId)}-calendar.json"
        fun calendarSecretKey(secret: String) = "calendar/$secret.json"

        /** For links in calendar entries. */
        private val WEB_URL = System.getenv("WEB_URL") ?: "https://artsplit.jacekgajek.com"
        fun attachmentKey(bandId: String, attachment: Attachment) =
            "bands/${safe(bandId)}/attachments/${safe(attachment.id)}.${attachment.kind.extension}"

        /** Ids end up in storage keys: allow only id-like characters. */
        private fun safe(id: String): String {
            badRequestIf(!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid id" }
            return id
        }

        private fun badRequestIf(condition: Boolean, message: () -> String) {
            if (condition) throw ApiError(400, "bad_request", message())
        }

        private fun <T> encode(serializer: KSerializer<T>, value: T) = ArtSplitJson.encodeToString(serializer, value)
        private fun <T> decode(serializer: KSerializer<T>, text: String) = ArtSplitJson.decodeFromString(serializer, text)
    }
}
