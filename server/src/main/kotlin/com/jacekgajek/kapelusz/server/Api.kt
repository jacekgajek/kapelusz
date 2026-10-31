package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.DeviceRegistration
import com.jacekgajek.kapelusz.api.MapLinkRequest
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.ops.Limits
import kotlinx.serialization.SerializationException

/**
 * The HTTP API (ITERATION_1 §5.3): authentication and routing; the handlers do the work, one class per area
 * (ITERATION_3 R12). Every operation goes through the shared [com.jacekgajek.kapelusz.ops.Engine], i.e. the same
 * permission checks and reducer the apps run, so clients cannot bypass the role rules.
 */
class Api(
    store: BlobStore,
    historyStore: HistoryStore,
    private val verifier: TokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessions: SessionTokens = SessionTokens(store, clock),
    /** No minimum by default (tests); the Lambda handler passes [MIN_APP_VERSION]. */
    private val minAppVersion: Int = 0,
    maxBandBytes: Int = MAX_BAND_BYTES,
    /** Place suggestions for Location (ITERATION_5 §9); null where no Google key is set up. */
    placeSearch: PlaceSearch? = null,
    /** Addresses already looked up, for the map (ITERATION_7 §8); null: none are. */
    geocodeCache: GeocodeCache? = null,
    /** The web app's page, for link cards (ITERATION_6 §2); null where it can't be read. */
    webIndex: WebIndex? = null,
    /** Sync messages to Android devices (ITERATION_7 §2); null where FCM isn't set up: devices register, nothing is sent. */
    messageSender: MessageSender? = null,
) {
    private val revocations = SessionRevocations(store, clock)
    private val bands = BandAccess(store, clock)
    private val devices = DeviceRegistry(store, clock)
    private val messenger: SyncMessenger? = messageSender?.let { DeviceMessenger(devices, it) }
    private val users = UserHandlers(store, verifier, sessions, revocations, clock, bands, devices)
    private val deviceHandlers = DeviceHandlers(devices, users) { messageSender?.ready() == true }
    private val history = HistoryHandlers(historyStore, clock, bands)
    private val attachments = AttachmentHandlers(store, bands)
    private val invites = InviteHandlers(store, clock, bands, users, history, messenger)
    private val calendar = CalendarHandlers(store, clock, bands, users)
    private val places = PlacesHandlers(bands, placeSearch, geocodeCache)
    private val linkPages = LinkPages(store, webIndex, clock, bands)
    private val deletions = BandDeletions(store, clock, bands, users, invites, linkPages, history, messenger).also { bands.whenDue = it::deleteNow }
    private val bandHandlers = BandHandlers(store, clock, maxBandBytes, bands, users, history, invites, attachments, linkPages, messenger, deletions)

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
        // The Android device making a change: no sync message for it about its own change (ITERATION_7 §2.4).
        val installation = request.query[ApiErrors.INSTALLATION_PARAMETER]?.takeIf { DeviceHandlers.INSTALLATION_ID.matches(it) }
            ?.let { SenderDevice(user.userId, it) }
        return routes(request) {
            // The user: account, devices, calendar subscription, sessions.
            get("me") { users.me(user) }
            post("me/picture") { users.picture(user, request) }
            put("me/devices/{installationId}") { (installationId) ->
                deviceHandlers.register(user, installationId, body(DeviceRegistration.serializer()))
            }
            delete("me/devices/{installationId}") { (installationId) -> deviceHandlers.remove(user, installationId) }
            get("me/calendar") { calendar.subscription(user, CalendarSubscriptionChange()) }
            post("me/calendar") { calendar.subscription(user, body(CalendarSubscriptionChange.serializer())) }
            post("session") { users.session(user) }
            post("sessions/revoke") { users.revokeSessions(user) }

            // A band: its file, changes to it, membership, history.
            post("bands") { bandHandlers.create(user, body(CreateBandRequest.serializer())) }
            get("bands/{bandId}/state") { (bandId) -> bandHandlers.state(user, bandId, request.header("if-none-match")) }
            post("bands/{bandId}/ops") { (bandId) -> bandHandlers.push(user, bandId, body(PushRequest.serializer()), installation) }
            delete("bands/{bandId}") { (bandId) -> bandHandlers.delete(user, bandId, installation) }
            post("bands/{bandId}/leave") { (bandId) -> bandHandlers.leave(user, bandId, installation) }
            get("bands/{bandId}/raw") { (bandId) -> bandHandlers.raw(user, bandId) }
            get("bands/{bandId}/log") { (bandId) ->
                history.history(user, bandId, request.query["month"], request.query["before"], request.query["months"])
            }

            // Places: suggestions while typing a location, the map's addresses.
            get("bands/{bandId}/places") { (bandId) -> places.suggest(user, bandId, request) }
            post("bands/{bandId}/places/resolve") { (bandId) -> places.resolve(user, bandId, request, body(MapLinkRequest.serializer())) }
            get("bands/{bandId}/places/{placeId}") { (bandId, placeId) -> places.details(user, bandId, placeId, request) }
            get("bands/{bandId}/locations") { (bandId) -> places.locations(user, bandId) }

            // Attachments: presigned addresses for uploading and downloading files.
            post("bands/{bandId}/attachments") { (bandId) -> attachments.upload(user, bandId, body(AttachmentUploadRequest.serializer())) }
            get("bands/{bandId}/attachments/{attachmentId}") { (bandId, attachmentId) -> attachments.download(user, bandId, attachmentId) }

            // Invites: a band's, and joining one by its code.
            get("bands/{bandId}/invites") { (bandId) -> invites.list(user, bandId) }
            post("bands/{bandId}/invites") { (bandId) -> invites.create(user, bandId, body(CreateInviteRequest.serializer())) }
            delete("bands/{bandId}/invites/{code}") { (bandId, code) -> invites.revoke(user, bandId, code) }
            get("invites/{code}") { (code) -> invites.preview(user, code) }
            post("invites/{code}/accept") { (code) -> invites.accept(user, code, installation) }
        } ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No route for ${request.method} ${request.path}")
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
        fun isGooglePhoto(url: String): Boolean =
            url.length <= 500 && Regex("https://[a-z0-9-]+\\.googleusercontent\\.com/[^\\s\"<>]*").matches(url)

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
