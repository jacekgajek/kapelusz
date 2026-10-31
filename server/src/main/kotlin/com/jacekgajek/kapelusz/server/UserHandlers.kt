package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PictureRequest
import com.jacekgajek.kapelusz.api.SessionResponse

/** `/me`, `/session` and `/sessions/revoke`, and the user records (band lists, photos) the other handlers keep up to date. */
internal class UserHandlers(
    private val store: BlobStore,
    private val verifier: TokenVerifier,
    private val sessions: SessionTokens,
    private val revocations: SessionRevocations,
    private val clock: () -> Long,
    private val bands: BandAccess,
    private val devices: DeviceRegistry,
) {
    fun me(user: VerifiedUser): HttpResponse {
        val record = loadUser(user.userId)
        val list = record.bandIds.mapNotNull { id ->
            val band = bands.load(id)?.first ?: return@mapNotNull null
            // Archived members are not shown the band (the app then finds it gone, ITERATION_3 R9); restoring them shows it again.
            val member = band.memberOfUser(user.userId)?.takeIf { !it.archived } ?: return@mapNotNull null
            BandSummary(band.id, band.name, member.name, member.role)
        }
        return ok(MeResponse.serializer(), MeResponse(user.userId, user.email, user.name, list))
    }

    fun picture(user: VerifiedUser, request: HttpRequest): HttpResponse {
        // The Google token the app kept from signing in: the photo in it is the user's (ITERATION_2 §7.4).
        val token = parse(request, PictureRequest.serializer()).googleToken
        val picture = verifier.pictureOf(token, user.userId) ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "Not a Google sign-in token of this user")
        rememberPicture(user.userId, picture)
        return HttpResponse(204)
    }

    /** A new session for a Google ID token, or a renewed one for a current session (sliding expiry). */
    fun session(user: VerifiedUser): HttpResponse {
        // A renewal that would keep a session alive too long: the app signs in with Google again.
        user.sessionSince?.let { since -> if (clock() - since > SessionTokens.MAX_AGE_MS) throw ApiError(401, ApiErrors.UNAUTHORIZED, "Sign in again") }
        // Signing in with Google brings the current photo (ITERATION_2 §7.4); a failure must not stop the sign-in.
        user.picture?.let { picture -> runCatching { rememberPicture(user.userId, picture) }.onFailure { System.err.println("Could not update the photo: $it") } }
        val (token, expiresAt) = sessions.issue(user)
        return ok(SessionResponse.serializer(), SessionResponse(token, expiresAt))
    }

    /** "Sign out on all devices" (ITERATION_3 R6): every session of the user so far, this one included, stops working. */
    fun revokeSessions(user: VerifiedUser): HttpResponse {
        revocations.revoke(user.userId)
        // Signed out everywhere: no device of theirs gets sync messages until it signs in again and registers.
        runCatching { devices.clear(user.userId) }
        return HttpResponse(204)
    }

    fun loadUser(userId: String): UserRecord = store.get(StorageKeys.user(userId))?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)

    fun pictureOf(userId: String): String? = runCatching { store.get(StorageKeys.user(userId))?.let { decode(UserRecord.serializer(), it.content).pictureUrl } }.getOrNull()

    /** Keeps [picture] with the user and puts it on their member in each of their bands (a Google photo only). */
    private fun rememberPicture(userId: String, picture: String) {
        val url = picture.takeIf { Api.isGooglePhoto(it) } ?: return
        val key = StorageKeys.user(userId)
        retryOnConflict(exhausted = {}) {
            val blob = store.get(key)
            val record = blob?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)
            if (record.pictureUrl != url) {
                store.put(key, encode(UserRecord.serializer(), record.copy(pictureUrl = url)), ifMatch = blob?.etag, ifNoneMatch = blob == null)
            }
            record.bandIds.forEach { bandId -> runCatching { bands.setMemberPicture(bandId, userId, url) } }
        }
    }

    /** At most [Api.MAX_BANDS_PER_USER] bands (ITERATION_3 R6); joining or creating one more is refused. */
    fun requireRoomForBand(userId: String, bandId: String) {
        val list = store.get(StorageKeys.user(userId))?.let { decode(UserRecord.serializer(), it.content).bandIds }.orEmpty()
        if (bandId in list || list.size < Api.MAX_BANDS_PER_USER) return
        // Only bands the user is still linked in count (archived too: they can be restored); the others are dropped
        // from their list here.
        val members = list.associateWith { id -> bands.load(id)?.first?.memberOfUser(userId) }
        members.filterValues { it == null }.keys.forEach { removeBandFromUser(userId, it) }
        if (members.values.count { it != null } >= Api.MAX_BANDS_PER_USER) {
            throw ApiError(409, ApiErrors.TOO_MANY_BANDS, "At most ${Api.MAX_BANDS_PER_USER} bands")
        }
    }

    fun addBandToUser(userId: String, bandId: String) {
        val key = StorageKeys.user(userId)
        retryOnConflict(exhausted = { throw busy("Could not update the user record") }) {
            val blob = store.get(key)
            val record = blob?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)
            if (bandId in record.bandIds) return@retryOnConflict
            store.put(key, encode(UserRecord.serializer(), record.copy(bandIds = record.bandIds + bandId)), ifMatch = blob?.etag, ifNoneMatch = blob == null)
        }
    }

    /**
     * After leaving their last band, what the server keeps of a user goes (what Play asks of account deletion): the
     * photo, the devices for sync messages, the calendar link and invite attempts. Only the account id and the time of "Sign out on all devices"
     * stay, so sessions ended then don't work again.
     */
    fun forgetIfNoBands(userId: String) {
        val key = StorageKeys.user(userId)
        val forgotten = retryOnConflict(exhausted = { false }) {
            val blob = store.get(key) ?: return@retryOnConflict false
            val record = decode(UserRecord.serializer(), blob.content)
            // Still in a band (archived too, it can be restored): nothing goes.
            if (record.bandIds.any { id -> bands.load(id)?.first?.memberOfUser(userId) != null }) return@retryOnConflict false
            store.put(key, encode(UserRecord.serializer(), UserRecord(userId, sessionsValidFrom = record.sessionsValidFrom)), ifMatch = blob.etag)
            true
        }
        if (!forgotten) return
        runCatching { devices.clear(userId) }
        store.get(StorageKeys.calendar(userId))?.let { blob ->
            runCatching { store.delete(StorageKeys.calendarSecret(decode(StoredCalendar.serializer(), blob.content).secret)) }
            store.delete(StorageKeys.calendar(userId))
        }
        store.delete(StorageKeys.joinAttempts(userId))
    }

    fun removeBandFromUser(userId: String, bandId: String) {
        val key = StorageKeys.user(userId)
        retryOnConflict(exhausted = {}) {
            val blob = store.get(key) ?: return@retryOnConflict
            val record = decode(UserRecord.serializer(), blob.content)
            if (bandId !in record.bandIds) return@retryOnConflict
            store.put(key, encode(UserRecord.serializer(), record.copy(bandIds = record.bandIds - bandId)), ifMatch = blob.etag)
        }
    }
}
