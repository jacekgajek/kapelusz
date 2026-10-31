package com.jacekgajek.kapelusz.server.invites

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.server.Api
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.busy
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.retryOnConflict
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.InviteCodes
import com.jacekgajek.kapelusz.server.store.JoinAttempts
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.StoredInvite
import com.jacekgajek.kapelusz.server.store.inviteCodes
import com.jacekgajek.kapelusz.server.store.joinAttempts
import java.security.SecureRandom

/**
 * Invite codes as stored (`invites/{code}.json`, and each band's list of its codes), and the join attempts counted per
 * user; the HTTP side is [InviteHandlers].
 */
internal class Invites(private val store: BlobStore, private val clock: () -> Long) {
    fun load(code: String): StoredInvite? = store.get(StorageKeys.invite(code))?.let { decode(StoredInvite.serializer(), it.content) }

    /** The band's invite codes, active or not. */
    fun loadCodes(bandId: String): List<String> = store.inviteCodes(bandId).get()?.codes ?: emptyList()

    /** A new invite with a code of its own, listed for its band. */
    fun create(bandId: String, memberId: String, role: Role, createdBy: String): InviteInfo {
        // A precondition failure here is a code collision: draw another one.
        val info = retryOnConflict(exhausted = { throw busy("Could not create an invite code") }) {
            val info = InviteInfo(newCode(), bandId, memberId, role, clock() + Api.INVITE_LIFETIME_MS)
            store.put(StorageKeys.invite(info.code), encode(StoredInvite.serializer(), StoredInvite(info, createdBy)), ifNoneMatch = true)
            info
        }
        updateCodes(bandId) { it + info.code }
        return info
    }

    fun delete(bandId: String, code: String) {
        store.delete(StorageKeys.invite(code))
        updateCodes(bandId) { codes -> codes - code }
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
     * Reserves one of the user's [Api.MAX_JOIN_ATTEMPTS] attempts per hour with a conditional write, so parallel
     * requests cannot get around the limit; none left: [tooManyAttempts].
     */
    fun reserveJoinAttempt(userId: String) {
        // Exhausted: parallel requests kept counting first, so there are plenty of attempts in this hour anyway.
        store.joinAttempts(userId).update(exhausted = { throw tooManyAttempts() }) { stored ->
            val current = stored?.takeIf { clock() - it.windowStart < Api.ATTEMPT_WINDOW_MS }
            if (current != null && current.failures >= Api.MAX_JOIN_ATTEMPTS) throw tooManyAttempts()
            current?.copy(failures = current.failures + 1) ?: JoinAttempts(clock(), 1)
        }
    }

    private fun tooManyAttempts() = ApiError(429, ApiErrors.TOO_MANY_ATTEMPTS, "Too many attempts, try again later")

    private fun updateCodes(bandId: String, change: (List<String>) -> List<String>) {
        store.inviteCodes(bandId).update(exhausted = { throw busy("Could not update invites") }) { stored ->
            InviteCodes(change(stored?.codes.orEmpty()))
        }
    }

    private fun newCode(): String = (1..Api.INVITE_LENGTH).map { Api.INVITE_ALPHABET[random.nextInt(Api.INVITE_ALPHABET.length)] }.joinToString("")

    private companion object {
        val random = SecureRandom()
    }
}
