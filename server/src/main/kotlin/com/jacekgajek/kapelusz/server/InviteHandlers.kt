package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.InvitePreview
import com.jacekgajek.kapelusz.api.InvitesResponse
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.domain.Viewers
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.withPicture
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.RejectionReason
import java.security.SecureRandom

/** Invite codes: leaders create them for an unlinked member, and whoever has the code joins the band as that member. */
internal class InviteHandlers(
    private val store: BlobStore,
    private val clock: () -> Long,
    private val bands: BandAccess,
    private val users: UserHandlers,
    private val history: HistoryHandlers,
    private val messenger: SyncMessenger?,
) {
    fun list(user: VerifiedUser, bandId: String): HttpResponse {
        bands.loadForLeader(user, bandId)
        val active = loadCodes(bandId).mapNotNull { load(it)?.info }.filter { it.expiresAt > clock() }
        return ok(InvitesResponse.serializer(), InvitesResponse(active))
    }

    fun create(user: VerifiedUser, bandId: String, request: CreateInviteRequest): HttpResponse {
        val (band, _) = bands.loadForLeader(user, bandId)
        val member = band.member(request.memberId) ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No such member")
        badRequestIf(member.userId != null) { "This member is already linked to an account" }
        badRequestIf(member.archived) { "This member is archived" }
        badRequestIf(request.role == Role.OWNER) { "Invites cannot grant ownership" }
        if (request.role == Role.ARTIST && Artists.holdsMoney(band, member.id)) throw holdsMoney()
        if (request.role == Role.VIEWER && !Viewers.mayBecome(band, member.id)) throw stillPlays()
        // A new code for the same member replaces the old one.
        loadCodes(bandId).forEach { code -> if (load(code)?.info?.memberId == member.id) delete(bandId, code) }
        // A precondition failure here is a code collision: draw another one.
        val info = retryOnConflict(exhausted = { throw busy("Could not create an invite code") }) {
            val info = InviteInfo(newCode(), bandId, member.id, request.role, clock() + Api.INVITE_LIFETIME_MS)
            store.put(StorageKeys.invite(info.code), encode(StoredInvite.serializer(), StoredInvite(info, user.userId)), ifNoneMatch = true)
            info
        }
        updateCodes(bandId) { it + info.code }
        return ok(InviteInfo.serializer(), info)
    }

    fun revoke(user: VerifiedUser, bandId: String, code: String): HttpResponse {
        bands.loadForLeader(user, bandId)
        // Codes are stored upper-case, as preview and accept read them (V5-M1 A10d).
        val stored = code.uppercase()
        if (load(stored)?.info?.bandId == bandId) delete(bandId, stored)
        return HttpResponse(204)
    }

    fun preview(user: VerifiedUser, code: String): HttpResponse {
        val invite = find(user, code).info
        val band = bands.load(invite.bandId)?.first ?: throw notFound()
        val member = band.member(invite.memberId) ?: throw notFound()
        return ok(InvitePreview.serializer(), InvitePreview(invite.code, band.id, band.name, member.name, invite.role, invite.expiresAt))
    }

    fun accept(user: VerifiedUser, code: String, from: SenderDevice? = null): HttpResponse {
        val stored = find(user, code)
        val invite = stored.info
        users.requireRoomForBand(user.userId, invite.bandId)
        return retryOnConflict(exhausted = { throw busy("Too many concurrent changes, try again") }) {
            val (band, etag) = bands.load(invite.bandId) ?: throw notFound()
            band.memberOfUser(user.userId)?.let { existing ->
                if (existing.id != invite.memberId) throw ApiError(409, ApiErrors.ALREADY_MEMBER, "You are already a member of this band")
                // A retry after an interrupted accept: the link is done, finish what may not have been.
                finishJoin(user, band.id, invite.memberId)
                return@retryOnConflict ok(BandStateResponse.serializer(), BandStateResponse(etag, band))
            }
            // The invite only stands while its issuer is still an active leader and the member is still in the band.
            val issuer = band.memberOfUser(stored.createdBy)
            val member = band.member(invite.memberId)
            if (issuer == null || issuer.archived || !issuer.role.atLeast(Role.LEAD) || member == null || member.archived) {
                delete(band.id, invite.code)
                throw notFound()
            }
            val linked = when (val result = Engine.apply(band, Actor.System, LinkUser(invite.memberId, user.userId, invite.role), "invite-${invite.code}", clock())) {
                is ApplyResult.Applied -> result.band.withPicture(user.userId, users.pictureOf(user.userId))

                // Became an artist while holding money meanwhile (ITERATION_4 §1.1): the invite stays for when it is settled.
                is ApplyResult.Rejected -> when (result.rejection.reason) {
                    RejectionReason.HOLDS_MONEY -> throw holdsMoney()
                    RejectionReason.VIEWER_STILL_PLAYS -> throw stillPlays()
                    else -> throw notFound()
                }
            }
            val newEtag = store.put(StorageKeys.band(band.id), encode(Band.serializer(), linked), ifMatch = etag)
            finishJoin(user, band.id, invite.memberId)
            runCatching { history.append(user, linked, band.id, listOf(OpEnvelope("invite-${invite.code}", "server", 0, clock(), LinkUser(invite.memberId, user.userId, invite.role))), emptyMap()) }
            // Bandmates see the new member at once (ITERATION_7 §2.2).
            runCatching { messenger?.bandChanged(band.id, linked.messagedUsers(), from) }
            ok(BandStateResponse.serializer(), BandStateResponse(newEtag, linked))
        }
    }

    /** After a join: list the band for the user and cancel every invite for the member (both idempotent). */
    private fun finishJoin(user: VerifiedUser, bandId: String, memberId: String) {
        users.addBandToUser(user.userId, bandId)
        // Again now that the band is listed: a sign-in at the same moment may have missed it.
        users.pictureOf(user.userId)?.let { url -> runCatching { bands.setMemberPicture(bandId, user.userId, url) } }
        revokeFor(bandId, memberId)
    }

    /**
     * Cancels all invites for a member. Called whenever the member's account link changes, so no leftover code (from a
     * race or an interrupted request) can link someone after a leader unlinked them.
     */
    fun revokeFor(bandId: String, memberId: String) {
        loadCodes(bandId).forEach { code ->
            val invite = load(code)
            if (invite == null || invite.info.memberId == memberId) delete(bandId, code)
        }
    }

    /**
     * Looks up an active invite. Every lookup first reserves one of the user's [Api.MAX_JOIN_ATTEMPTS] attempts per hour
     * with a conditional write, so parallel requests cannot get around the limit. A real join needs two (preview, accept).
     */
    private fun find(user: VerifiedUser, rawCode: String): StoredInvite {
        reserveJoinAttempt(user.userId)
        val code = rawCode.uppercase()
        return code.takeIf { it.matches(Regex("[${Api.INVITE_ALPHABET}]{${Api.INVITE_LENGTH}}")) }?.let { load(it) }
            ?.takeIf { it.info.expiresAt > clock() }
            ?: throw notFound()
    }

    private fun reserveJoinAttempt(userId: String) {
        val key = StorageKeys.joinAttempts(userId)
        // Exhausted: parallel requests kept counting first, so there are plenty of attempts in this hour anyway.
        retryOnConflict(exhausted = { throw tooManyAttempts() }) {
            val blob = store.get(key)
            val current = blob?.let { decode(JoinAttempts.serializer(), it.content) }?.takeIf { clock() - it.windowStart < Api.ATTEMPT_WINDOW_MS }
            if (current != null && current.failures >= Api.MAX_JOIN_ATTEMPTS) throw tooManyAttempts()
            val updated = current?.copy(failures = current.failures + 1) ?: JoinAttempts(clock(), 1)
            store.put(key, encode(JoinAttempts.serializer(), updated), ifMatch = blob?.etag, ifNoneMatch = blob == null)
        }
    }

    private fun tooManyAttempts() = ApiError(429, ApiErrors.TOO_MANY_ATTEMPTS, "Too many attempts, try again later")

    private fun notFound() = ApiError(404, ApiErrors.INVITE_NOT_FOUND, "No such invite")

    private fun load(code: String): StoredInvite? = store.get(StorageKeys.invite(code))?.let { decode(StoredInvite.serializer(), it.content) }

    /** The band's invite codes, active or not. */
    fun loadCodes(bandId: String): List<String> = store.get(StorageKeys.bandInvites(bandId))?.let { decode(InviteCodes.serializer(), it.content).codes } ?: emptyList()

    private fun updateCodes(bandId: String, change: (List<String>) -> List<String>) {
        val key = StorageKeys.bandInvites(bandId)
        retryOnConflict(exhausted = { throw busy("Could not update invites") }) {
            val blob = store.get(key)
            val codes = blob?.let { decode(InviteCodes.serializer(), it.content).codes } ?: emptyList()
            store.put(key, encode(InviteCodes.serializer(), InviteCodes(change(codes))), ifMatch = blob?.etag, ifNoneMatch = blob == null)
        }
    }

    private fun delete(bandId: String, code: String) {
        store.delete(StorageKeys.invite(code))
        updateCodes(bandId) { codes -> codes - code }
    }

    private fun newCode(): String = (1..Api.INVITE_LENGTH).map { Api.INVITE_ALPHABET[random.nextInt(Api.INVITE_ALPHABET.length)] }.joinToString("")

    companion object {
        private val random = SecureRandom()
    }

    private fun holdsMoney() = ApiError(409, ApiErrors.HOLDS_MONEY, "They still hold band money: settle it first")

    private fun stillPlays() = ApiError(409, ApiErrors.STILL_PLAYS, "They still play in events not settled yet or hold the band fund")
}
