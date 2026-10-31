package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors

/**
 * The user records (`users/{googleSub}.json`: band lists, photos, "signed out everywhere"), which every handler that
 * changes a user's bands keeps up to date; the HTTP side is [UserHandlers].
 */
internal class UserRecords(private val store: BlobStore, private val bands: BandAccess, private val devices: DeviceRegistry) {
    fun load(userId: String): UserRecord = store.userRecord(userId).get() ?: UserRecord(userId)

    fun pictureOf(userId: String): String? = runCatching { store.userRecord(userId).get()?.pictureUrl }.getOrNull()

    /** Keeps [picture] with the user and puts it on their member in each of their bands (a Google photo only). */
    fun rememberPicture(userId: String, picture: String) {
        val url = picture.takeIf { Api.isGooglePhoto(it) } ?: return
        val record = store.userRecord(userId).update(exhausted = { null }) { stored ->
            (stored ?: UserRecord(userId)).copy(pictureUrl = url)
        }
        record?.bandIds?.forEach { bandId -> runCatching { bands.setMemberPicture(bandId, userId, url) } }
    }

    /** At most [Api.MAX_BANDS_PER_USER] bands (ITERATION_3 R6); joining or creating one more is refused. */
    fun requireRoomForBand(userId: String, bandId: String) {
        val list = store.userRecord(userId).get()?.bandIds.orEmpty()
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
        store.userRecord(userId).update { stored ->
            val record = stored ?: UserRecord(userId)
            if (bandId in record.bandIds) record else record.copy(bandIds = record.bandIds + bandId)
        }
    }

    /**
     * After leaving their last band, what the server keeps of a user goes (what Play asks of account deletion): the
     * photo, the devices for sync messages, the calendar link and invite attempts. Only the account id and the time of
     * "Sign out on all devices" stay, so sessions ended then don't work again.
     */
    fun forgetIfNoBands(userId: String) {
        val document = store.userRecord(userId)
        val forgotten = retryOnConflict(exhausted = { false }) {
            val (record, etag) = document.read() ?: return@retryOnConflict false
            // Still in a band (archived too, it can be restored): nothing goes.
            if (record.bandIds.any { id -> bands.load(id)?.first?.memberOfUser(userId) != null }) return@retryOnConflict false
            document.write(UserRecord(userId, sessionsValidFrom = record.sessionsValidFrom), etag)
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
        store.userRecord(userId).update(exhausted = { null }) { record ->
            record?.let { if (bandId !in it.bandIds) it else it.copy(bandIds = it.bandIds - bandId) }
        }
    }
}
