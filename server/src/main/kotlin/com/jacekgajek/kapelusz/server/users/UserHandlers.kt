package com.jacekgajek.kapelusz.server.users

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PictureRequest
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.Call
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.auth.SessionRevocations
import com.jacekgajek.kapelusz.server.auth.SessionTokens
import com.jacekgajek.kapelusz.server.auth.TokenVerifier
import com.jacekgajek.kapelusz.server.bands.BandAccess
import com.jacekgajek.kapelusz.server.devices.DeviceRegistry
import com.jacekgajek.kapelusz.server.ok
import com.jacekgajek.kapelusz.server.store.devices

/** `/me`, `/me/picture`, `/session` and `/sessions/revoke`; the records behind them are [UserRecords]. */
internal class UserHandlers(
    private val verifier: TokenVerifier,
    private val sessions: SessionTokens,
    private val revocations: SessionRevocations,
    private val clock: () -> Long,
    private val bands: BandAccess,
    private val devices: DeviceRegistry,
    private val users: UserRecords,
) {
    fun me(call: Call): HttpResponse {
        val user = call.user
        val record = users.load(user.userId)
        val list = record.bandIds.mapNotNull { id ->
            val band = bands.load(id)?.first ?: return@mapNotNull null
            // Archived members are not shown the band (the app then finds it gone, I3 R9); restoring them shows it again.
            val member = band.memberOfUser(user.userId)?.takeIf { !it.archived } ?: return@mapNotNull null
            BandSummary(band.id, band.name, member.name, member.role)
        }
        return ok(MeResponse.serializer(), MeResponse(user.userId, user.email, user.name, list))
    }

    fun picture(call: Call): HttpResponse {
        val user = call.user
        // The Google token the app kept from signing in: the photo in it is the user's (I2 §7.4).
        val token = call.body(PictureRequest.serializer()).googleToken
        val picture = verifier.pictureOf(token, user.userId) ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "Not a Google sign-in token of this user")
        users.rememberPicture(user.userId, picture)
        return HttpResponse(204)
    }

    /** A new session for a Google ID token, or a renewed one for a current session (sliding expiry). */
    fun session(call: Call): HttpResponse {
        val user = call.user
        // A renewal that would keep a session alive too long: the app signs in with Google again.
        user.sessionSince?.let { since -> if (clock() - since > SessionTokens.MAX_AGE_MS) throw ApiError(401, ApiErrors.UNAUTHORIZED, "Sign in again") }
        // Signing in with Google brings the current photo (I2 §7.4); a failure must not stop the sign-in.
        user.picture?.let { picture -> runCatching { users.rememberPicture(user.userId, picture) }.onFailure { System.err.println("Could not update the photo: $it") } }
        val (token, expiresAt) = sessions.issue(user)
        return ok(SessionResponse.serializer(), SessionResponse(token, expiresAt))
    }

    /** "Sign out on all devices" (I3 R6): every session of the user so far, this one included, stops working. */
    fun revokeSessions(call: Call): HttpResponse {
        val user = call.user
        revocations.revoke(user.userId)
        // Signed out everywhere: no device of theirs gets sync messages until it signs in again and registers.
        runCatching { devices.clear(user.userId) }
        return HttpResponse(204)
    }
}
