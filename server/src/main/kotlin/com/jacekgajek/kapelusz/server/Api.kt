package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.server.attachments.AttachmentFiles
import com.jacekgajek.kapelusz.server.attachments.AttachmentHandlers
import com.jacekgajek.kapelusz.server.auth.SessionRevocations
import com.jacekgajek.kapelusz.server.auth.SessionTokens
import com.jacekgajek.kapelusz.server.auth.TokenVerifier
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.bands.BandAccess
import com.jacekgajek.kapelusz.server.bands.BandChanged
import com.jacekgajek.kapelusz.server.bands.BandDeletions
import com.jacekgajek.kapelusz.server.bands.BandHandlers
import com.jacekgajek.kapelusz.server.calendar.CalendarHandlers
import com.jacekgajek.kapelusz.server.devices.DeviceHandlers
import com.jacekgajek.kapelusz.server.devices.DeviceRegistry
import com.jacekgajek.kapelusz.server.devices.SenderDevice
import com.jacekgajek.kapelusz.server.history.HistoryHandlers
import com.jacekgajek.kapelusz.server.history.HistoryLog
import com.jacekgajek.kapelusz.server.invites.InviteHandlers
import com.jacekgajek.kapelusz.server.invites.Invites
import com.jacekgajek.kapelusz.server.links.EventPointers
import com.jacekgajek.kapelusz.server.links.LinkPages
import com.jacekgajek.kapelusz.server.links.NoWebIndex
import com.jacekgajek.kapelusz.server.links.WebIndex
import com.jacekgajek.kapelusz.server.messaging.DeviceMessenger
import com.jacekgajek.kapelusz.server.messaging.MessageSender
import com.jacekgajek.kapelusz.server.messaging.NoopMessageSender
import com.jacekgajek.kapelusz.server.messaging.SyncMessenger
import com.jacekgajek.kapelusz.server.places.PlaceSearch
import com.jacekgajek.kapelusz.server.places.PlacesHandlers
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.GeocodeCache
import com.jacekgajek.kapelusz.server.store.HistoryStore
import com.jacekgajek.kapelusz.server.store.devices
import com.jacekgajek.kapelusz.server.users.UserHandlers
import com.jacekgajek.kapelusz.server.users.UserRecords
import kotlinx.serialization.SerializationException

/**
 * The HTTP API (ITERATION_1 §5.3): authentication and routing; the handlers do the work, one class per area
 * (ITERATION_3 R12). Every operation goes through the shared [com.jacekgajek.kapelusz.engine.Engine], i.e. the same
 * permission checks and reducer the apps run, so clients cannot bypass the role rules.
 */
class Api(
    store: BlobStore,
    historyStore: HistoryStore,
    private val verifier: TokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessions: SessionTokens = SessionTokens(store, clock),
    /** Place suggestions for Location (ITERATION_5 §9); null where no Google key is set up. */
    placeSearch: PlaceSearch? = null,
    /** Addresses already looked up, for the map (ITERATION_7 §8); null: none are. */
    geocodeCache: GeocodeCache? = null,
    /** The web app's page, for link cards (ITERATION_6 §2); [NoWebIndex] where it can't be had. */
    webIndex: WebIndex = NoWebIndex,
    /** Sync messages to Android devices (ITERATION_7 §2); [NoopMessageSender] where FCM isn't set up: devices register, nothing is sent. */
    messageSender: MessageSender = NoopMessageSender,
    /** What the deployment decides; tests take the defaults (no minimum app version). */
    private val config: ServerConfig = ServerConfig(),
) {
    private val revocations = SessionRevocations(store, clock)
    private val bands = BandAccess(store, clock)
    private val devices = DeviceRegistry(store, clock)
    private val messenger: SyncMessenger = DeviceMessenger(devices, messageSender)

    // What is stored, by resource; then the handlers over it.
    private val records = UserRecords(store, bands, devices)
    private val log = HistoryLog(historyStore, clock)
    private val codes = Invites(store, clock)
    private val files = AttachmentFiles(store)
    private val pointers = EventPointers(store, bands)
    private val users = UserHandlers(verifier, sessions, revocations, clock, bands, devices, records)
    private val deviceHandlers = DeviceHandlers(devices, records, messageSender::ready)
    private val history = HistoryHandlers(clock, bands, log)
    private val attachments = AttachmentHandlers(bands, files)
    private val deletions = BandDeletions(store, clock, bands, records, codes, pointers, log, messenger).also { bands.whenDue = it::deleteNow }
    private val changed = BandChanged(records, codes, pointers, files, deletions, log, messenger)
    private val invites = InviteHandlers(store, clock, bands, records, codes, changed)
    private val calendar = CalendarHandlers(store, clock, bands, records, config.webUrl)
    private val places = PlacesHandlers(bands, placeSearch, geocodeCache)
    private val linkPages = LinkPages(webIndex, bands, pointers)
    private val bandHandlers = BandHandlers(store, clock, config.maxBandBytes, bands, records, log, changed)

    /** [sweep] false while priming a SnapStart snapshot: nothing deleted, and no messaging key read, at publish time. */
    fun handle(request: HttpRequest, sweep: Boolean = true): HttpResponse = try {
        // Bands whose time to be deleted has come (ITERATION_7 §9), hourly: a failure there is not this request's.
        if (sweep) runCatching { deletions.sweep() }
        route(request)
    } catch (e: ApiError) {
        error(e.status, e.code, e.message.orEmpty())
    } catch (e: SerializationException) {
        error(400, ApiErrors.BAD_REQUEST, e.message.orEmpty())
    }

    private fun route(request: HttpRequest): HttpResponse = when {
        // CORS preflight: the catch-all API Gateway route forwards it here. Browsers send it without credentials and
        // need a 2xx answer; API Gateway adds the CORS headers.
        request.method == "OPTIONS" -> HttpResponse(204)

        // The calendar feed is fetched by calendar apps: no sign-in, no app version; the secret in the path is the key.
        request.isRead && CALENDAR_FEED.matches(request.path) -> calendarFeed(request)

        // Event links (ITERATION_6 §2) reach the API through the web app's CloudFront, without a sign-in.
        request.path.startsWith("/e/") -> eventLinkPage(request)

        !appSupported(request.query[ApiErrors.APP_VERSION_PARAMETER]) ->
            throw ApiError(426, ApiErrors.UPDATE_REQUIRED, "This version of the app is too old; update it")

        else -> signedIn(request)
    }

    private fun calendarFeed(request: HttpRequest): HttpResponse {
        val secret = request.path.removePrefix("/calendar/").removeSuffix(".ics")
        return calendar.feed(secret).answering(request)
    }

    /** The web app's page with the event's card. A 404 lets CloudFront answer with the plain page. */
    private fun eventLinkPage(request: HttpRequest): HttpResponse {
        val eventId = EVENT_LINK.matchEntire(request.path)?.groupValues?.get(1)?.takeIf { request.isRead }
            ?: throw ApiError(404, ApiErrors.NOT_FOUND, "Not an API path")
        return linkPages.event(eventId).answering(request)
    }

    /** The API proper: every other request needs a signed-in user. */
    private fun signedIn(request: HttpRequest): HttpResponse {
        val user = authenticate(request)
        val installation = request.query[ApiErrors.INSTALLATION_PARAMETER]?.takeIf { DeviceHandlers.INSTALLATION_ID.matches(it) }
            ?.let { SenderDevice(user.userId, it) }
        return router.handle(request) { params -> Call(request, user, params, installation) }
            ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No route for ${request.method} ${request.path}")
    }

    private val router = Router(
        listOf(
            // The user: account, devices, calendar subscription, sessions.
            Route("GET", "me", users::me),
            Route("POST", "me/picture", users::picture),
            Route("PUT", "me/devices/{installationId}", deviceHandlers::register),
            Route("DELETE", "me/devices/{installationId}", deviceHandlers::remove),
            Route("GET", "me/calendar", calendar::subscription),
            Route("POST", "me/calendar", calendar::changeSubscription),
            Route("POST", "session", users::session),
            Route("POST", "sessions/revoke", users::revokeSessions),
            // A band: its file, changes to it, membership, history.
            Route("POST", "bands", bandHandlers::create),
            Route("GET", "bands/{bandId}/state", bandHandlers::state),
            Route("POST", "bands/{bandId}/ops", bandHandlers::push),
            Route("DELETE", "bands/{bandId}", bandHandlers::delete),
            Route("POST", "bands/{bandId}/leave", bandHandlers::leave),
            Route("GET", "bands/{bandId}/raw", bandHandlers::raw),
            Route("GET", "bands/{bandId}/log", history::history),
            // Places: suggestions while typing a location, the map's addresses.
            Route("GET", "bands/{bandId}/places", places::suggest),
            Route("POST", "bands/{bandId}/places/resolve", places::resolve),
            Route("GET", "bands/{bandId}/places/{placeId}", places::details),
            Route("GET", "bands/{bandId}/locations", places::locations),
            // Attachments: presigned addresses for uploading and downloading files.
            Route("POST", "bands/{bandId}/attachments", attachments::upload),
            Route("GET", "bands/{bandId}/attachments/{attachmentId}", attachments::download),
            // Invites: a band's, and joining one by its code.
            Route("GET", "bands/{bandId}/invites", invites::list),
            Route("POST", "bands/{bandId}/invites", invites::create),
            Route("DELETE", "bands/{bandId}/invites/{code}", invites::revoke),
            Route("GET", "invites/{code}", invites::preview),
            Route("POST", "invites/{code}/accept", invites::accept),
        ),
    )

    /**
     * Apps send their version code; a version below [ServerConfig.minAppVersion] must update first. Apps from before
     * version 2 send none, and local development builds send 0 (always allowed, so they can be tested against the live
     * API).
     */
    private fun appSupported(version: String?): Boolean {
        val minAppVersion = config.minAppVersion
        if (minAppVersion <= 0) return true
        val code = version?.toIntOrNull() ?: return false
        return code == 0 || code >= minAppVersion
    }

    private fun authenticate(request: HttpRequest): VerifiedUser {
        val token = request.header("authorization")?.removePrefix("Bearer ")?.trim()
            ?: throw ApiError(401, ApiErrors.UNAUTHORIZED, "Missing bearer token")
        // Kapelusz sessions are recognised by their prefix; anything else must be a Google ID token.
        val user = if (token.startsWith(SessionTokens.PREFIX)) sessions.verify(token) else verifier.verify(token)
        user ?: throw ApiError(401, ApiErrors.UNAUTHORIZED, "Invalid or expired token")
        // A session, or a Google token, from before "sign out on all devices" (ITERATION_3 R6); also refuses renewing
        // the session or exchanging the Google token, which the other devices would otherwise do silently.
        (user.sessionSince ?: user.issuedAt)?.let { since ->
            if (!revocations.isValid(user.userId, since)) throw ApiError(401, ApiErrors.SESSIONS_REVOKED, "Signed out on all devices; sign in again")
        }
        return user
    }

    companion object {
        private val CALENDAR_FEED = Regex("/calendar/[A-Za-z0-9_-]{20,64}\\.ics")
        private val EVENT_LINK = Regex("/e/([A-Za-z0-9_-]{1,64})/?")

        /**
         * The oldest app version (its version code: the run number of the CI run that deployed it, plus 10 since
         * 2026-10-27; before 2026-10-25, the separate Deploy workflow's run number) the server still serves; older apps
         * show "update required".
         *
         * Raise it in two steps, never in the change that needs it: first deploy apps that handle the new data, then
         * set this to **that deploy's version code** (already live, so its web app and APK exist). Setting it to a
         * number not yet deployed would refuse every app, and during a deploy the server switches before the new web
         * app and APK are uploaded.
         */
        const val MIN_APP_VERSION = 12

        /** Tries of a read–modify–conditional-write before giving up (see [retryOnConflict]). */
        const val MAX_ATTEMPTS = 5
        const val MAX_OPS_PER_PUSH = 500
        const val MAX_DEVICES_PER_USER = 50
        const val HISTORY_PAGE = 100

        /** Google profile photos only: an https address on Google's image servers, of sensible length. */
        fun isGooglePhoto(url: String): Boolean = url.length <= 500 && Regex("https://[a-z0-9-]+\\.googleusercontent\\.com/[^\\s\"<>]*").matches(url)

        /** A history page stays well below the 6 MB a Lambda response may have. */
        const val HISTORY_PAGE_BYTES = 1_500_000
        const val MAX_UNDOS_PER_PUSH = Limits.MAX_UNDOS_PER_PUSH

        /** Bands one user may belong to (ITERATION_3 R6): far above any musician's, and a bound on storage per account. */
        const val MAX_BANDS_PER_USER = 20

        /**
         * The largest band file a push may produce, in UTF-8 bytes (ITERATION_3 R1, V5-M1 A2): well below the 6 MB of a
         * Lambda response, which also escapes it once more.
         */
        const val MAX_BAND_BYTES = 3_000_000

        const val INVITE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val INVITE_LENGTH = 6
        const val INVITE_LIFETIME_MS = 72 * 3600 * 1000L
        const val MAX_JOIN_ATTEMPTS = 10
        const val ATTEMPT_WINDOW_MS = 3600 * 1000L
    }
}

/** GET or HEAD: the requests the pages without a sign-in answer. */
private val HttpRequest.isRead get() = method == "GET" || method == "HEAD"

/** This page, without its body when [request] is a HEAD. */
private fun HttpResponse.answering(request: HttpRequest) = if (request.method == "HEAD") copy(body = null) else this
