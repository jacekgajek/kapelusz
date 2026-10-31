package com.jacekgajek.kapelusz.server.store

import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.server.badRequestIf
import kotlinx.serialization.Serializable

/**
 * Every key in the data bucket (one place, so the layout can be read off here). Ids from requests
 * end up in keys, so they are checked by [safe]; invite codes and calendar secrets are checked by their routes.
 */
object StorageKeys {
    fun band(bandId: String) = "${bandPrefix(bandId)}state.json"

    /** Everything stored for a band: state, invites, history and attachment files. */
    fun bandPrefix(bandId: String) = "bands/${safe(bandId)}/"
    fun bandInvites(bandId: String) = "${bandPrefix(bandId)}invites.json"
    fun deleted(bandId: String) = "${bandPrefix(bandId)}deleted.json"
    fun attachment(bandId: String, attachment: Attachment) = "${bandPrefix(bandId)}attachments/${safe(attachment.id)}.${attachment.kind.extension}"

    /** The band's history; an entry's name (its id in [com.jacekgajek.kapelusz.api.HistoryEntry]) is the rest of its key. */

    fun user(userId: String) = "users/${safe(userId)}.json"
    fun joinAttempts(userId: String) = "users/${safe(userId)}-join-attempts.json"
    fun calendar(userId: String) = "users/${safe(userId)}-calendar.json"

    /** The user's devices that get sync messages ([StoredDevices]). */
    fun devices(userId: String) = "users/${safe(userId)}-devices.json"
    fun calendarSecret(secret: String) = "calendar/$secret.json"
    fun invite(code: String) = "invites/$code.json"

    /** Which band an event is in ([EventPointer]), for its link's card. */
    fun eventPointer(eventId: String) = "events/${safe(eventId)}.json"

    /**
     * The `kapelusz-fcm` Google service account's JSON key, for sync messages ([StoredServiceAccount]); put by hand, never written by the
     * server.
     */
    const val FCM_KEY = "server/fcm-service-account.json"

    /** The key that signs session tokens (see [SessionTokens]). */
    const val SESSION_KEY = "server/session-key.json"

    /** The bands waiting to be deleted ([ScheduledDeletions]). */
    const val SCHEDULED_DELETIONS = "server/scheduled-deletions.json"

    /** Read once while priming a SnapStart snapshot; never written. */
    const val PRIMING = "priming/none.json"

    /** Ids end up in storage keys: allow only id-like characters. */
    fun safe(id: String): String {
        badRequestIf(!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid id" }
        return id
    }
}

/** The user's band list, stored as `users/{googleSub}.json`. */
@Serializable
data class UserRecord(
    val userId: String,
    val bandIds: List<String> = emptyList(),
    /** The Google photo from the last sign-in, for the user's member in bands they join or create. */
    val pictureUrl: String? = null,
    /** "Signed out everywhere": session tokens begun, and Google tokens issued, before this time (epoch millis) are refused. */
    val sessionsValidFrom: Long = 0,
)

/** Invite code lookups in the current hour, stored as `users/{googleSub}-join-attempts.json`. */
@Serializable
data class JoinAttempts(val windowStart: Long, val failures: Int)

/** Active invite codes of a band, stored as `bands/{id}/invites.json` (codes are only visible to leaders). */
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

/** The bands waiting to be deleted, `server/scheduled-deletions.json`: band id to when, epoch millis. */
@Serializable
data class ScheduledDeletions(val bands: Map<String, Long> = emptyMap())

/** An installation of the Android app that gets sync messages: its Firebase token, and when it last registered. */
@Serializable
data class StoredDevice(val installationId: String, val token: String, val lastSeen: Long)

/** `users/{userId}-devices.json`: the user's devices. */
@Serializable
data class StoredDevices(val devices: List<StoredDevice> = emptyList())
