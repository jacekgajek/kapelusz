package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** The user's calendar feed: its secret link, and the feed itself, which calendar apps fetch without signing in. */
internal class CalendarHandlers(
    private val store: BlobStore,
    private val clock: () -> Long,
    private val bands: BandAccess,
    private val users: UserHandlers,
) {
    /** The user's feed link, created on first use; [change] can switch "include all" or make a new link. */
    fun subscription(user: VerifiedUser, request: HttpRequest, change: CalendarSubscriptionChange): HttpResponse {
        val key = StorageKeys.calendar(user.userId)
        val (secret, updated) = retryOnConflict(exhausted = { throw busy("Try again") }) {
            val blob = store.get(key)
            val current = blob?.let { decode(StoredCalendar.serializer(), it.content) }
            val secret = if (current == null || change.reset) newSecret() else current.secret
            val updated = StoredCalendar(secret, change.includeAll ?: current?.includeAll ?: false)
            if (updated != current) {
                // The new secret's mapping first: a link handed out must always resolve. A mapping left over from a
                // failed attempt is harmless, since the feed also checks the user's current secret.
                if (secret != current?.secret) store.put(StorageKeys.calendarSecret(secret), encode(CalendarOwner.serializer(), CalendarOwner(user.userId)))
                store.put(key, encode(StoredCalendar.serializer(), updated), ifMatch = blob?.etag, ifNoneMatch = blob == null)
                if (current != null && secret != current.secret) runCatching { store.delete(StorageKeys.calendarSecret(current.secret)) }
            }
            secret to updated
        }
        // The link points at this API, whichever address the app used.
        val host = request.header("host") ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "No host")
        return ok(CalendarSubscription.serializer(), CalendarSubscription("https://$host/calendar/$secret.ics", updated.includeAll))
    }

    fun feed(secret: String): HttpResponse {
        val owner = store.get(StorageKeys.calendarSecret(secret))?.let { decode(CalendarOwner.serializer(), it.content) }
            ?: return error(404, ApiErrors.NOT_FOUND, "No such calendar")
        val settings = store.get(StorageKeys.calendar(owner.userId))?.let { decode(StoredCalendar.serializer(), it.content) }
        if (settings?.secret != secret) return error(404, ApiErrors.NOT_FOUND, "No such calendar")
        val list = users.loadUser(owner.userId).bandIds.mapNotNull { bands.load(it)?.first }
        val now = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDateTime()
        val stamp = kotlinx.datetime.LocalDateTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
        val body = CalendarFeed.build(list, owner.userId, settings.includeAll, stamp.date, WEB_URL, stamp)
        return HttpResponse(200, body, mapOf("content-type" to "text/calendar; charset=utf-8", "cache-control" to "private, max-age=900"))
    }

    private fun newSecret(): String = ByteArray(24).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private val random = SecureRandom()

        /** For links in calendar entries. */
        private val WEB_URL = System.getenv("WEB_URL") ?: "https://kapelusz.jacekgajek.com"
    }
}
