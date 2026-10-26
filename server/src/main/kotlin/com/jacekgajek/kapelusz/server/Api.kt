package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
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
    /** The web app's page, for link cards (ITERATION_6 §2); null where it can't be read. */
    webIndex: WebIndex? = null,
) {
    private val revocations = SessionRevocations(store, clock)
    private val bands = BandAccess(store)
    private val users = UserHandlers(store, verifier, sessions, revocations, clock, bands)
    private val history = HistoryHandlers(historyStore, clock, bands)
    private val attachments = AttachmentHandlers(store, bands)
    private val invites = InviteHandlers(store, clock, bands, users, history)
    private val calendar = CalendarHandlers(store, clock, bands, users)
    private val places = PlacesHandlers(bands, placeSearch)
    private val linkPages = LinkPages(store, webIndex, clock, bands)
    private val bandHandlers = BandHandlers(store, clock, maxBandBytes, bands, users, history, invites, attachments, linkPages)

    fun handle(request: HttpRequest): HttpResponse = try {
        route(request)
    } catch (e: ApiError) {
        error(e.status, e.code, e.message.orEmpty())
    } catch (e: SerializationException) {
        error(400, ApiErrors.BAD_REQUEST, e.message.orEmpty())
    }

    private fun route(request: HttpRequest): HttpResponse {
        // CORS preflight: the catch-all API Gateway route forwards it here. Browsers send it without credentials and
        // need a 2xx answer; API Gateway adds the CORS headers.
        if (request.method == "OPTIONS") return HttpResponse(204)
        // The calendar feed is fetched by calendar apps: no sign-in, no app version; the secret in the path is the key.
        val calendarFeed = Regex("^/calendar/([A-Za-z0-9_-]{20,64})\\.ics$").find(request.path)
        if ((request.method == "GET" || request.method == "HEAD") && calendarFeed != null) {
            return calendar.feed(calendarFeed.groupValues[1]).let { if (request.method == "HEAD") it.copy(body = null) else it }
        }
        // Event links (ITERATION_6 §2) reach the API through the web app's CloudFront, without a sign-in: the web app's
        // page with the event's card. A 404 lets CloudFront answer with the plain page.
        if (request.path.startsWith("/e/")) {
            if (request.method != "GET" && request.method != "HEAD") throw ApiError(404, ApiErrors.NOT_FOUND, "Not an API path")
            val page = Regex("^/e/([A-Za-z0-9_-]{1,64})/?$").find(request.path)?.let { linkPages.event(it.groupValues[1]) }
                ?: throw ApiError(404, ApiErrors.NOT_FOUND, "Not an API path")
            return if (request.method == "HEAD") page.copy(body = null) else page
        }
        if (!appSupported(request.query[ApiErrors.APP_VERSION_PARAMETER])) {
            throw ApiError(426, ApiErrors.UPDATE_REQUIRED, "This version of the app is too old; update it")
        }
        val user = authenticate(request)
        val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        val method = request.method
        // bands/{id}/{what}
        val bandPath = segments.size == 3 && segments[0] == "bands"
        return when {
            method == "GET" && segments == listOf("me") -> users.me(user)
            method == "POST" && segments == listOf("me", "picture") -> users.picture(user, request)
            method == "GET" && segments == listOf("me", "calendar") -> calendar.subscription(user, CalendarSubscriptionChange())
            method == "POST" && segments == listOf("me", "calendar") ->
                calendar.subscription(user, parse(request, CalendarSubscriptionChange.serializer()))
            method == "POST" && segments == listOf("session") -> users.session(user)
            method == "POST" && segments == listOf("sessions", "revoke") -> users.revokeSessions(user)
            method == "POST" && segments == listOf("bands") -> bandHandlers.create(user, parse(request, CreateBandRequest.serializer()))
            method == "GET" && bandPath && segments[2] == "state" -> bandHandlers.state(user, segments[1], request.header("if-none-match"))
            method == "POST" && bandPath && segments[2] == "ops" -> bandHandlers.push(user, segments[1], parse(request, PushRequest.serializer()))
            method == "DELETE" && segments.size == 2 && segments[0] == "bands" -> bandHandlers.delete(user, segments[1])
            method == "GET" && bandPath && segments[2] == "raw" -> bandHandlers.raw(user, segments[1])
            method == "GET" && bandPath && segments[2] == "log" -> history.history(user, segments[1], request.query["month"], request.query["before"], request.query["months"])
            method == "GET" && bandPath && segments[2] == "invites" -> invites.list(user, segments[1])
            method == "GET" && bandPath && segments[2] == "places" -> places.suggest(user, segments[1], request)
            method == "POST" && segments.size == 4 && segments[0] == "bands" && segments[2] == "places" && segments[3] == "resolve" ->
                places.resolve(user, segments[1], request, parse(request, MapLinkRequest.serializer()))
            method == "GET" && segments.size == 4 && segments[0] == "bands" && segments[2] == "places" -> places.details(user, segments[1], segments[3], request)
            method == "POST" && bandPath && segments[2] == "invites" ->
                invites.create(user, segments[1], parse(request, CreateInviteRequest.serializer()))
            method == "DELETE" && segments.size == 4 && segments[0] == "bands" && segments[2] == "invites" ->
                invites.revoke(user, segments[1], segments[3])
            method == "POST" && bandPath && segments[2] == "attachments" ->
                attachments.upload(user, segments[1], parse(request, AttachmentUploadRequest.serializer()))
            method == "GET" && segments.size == 4 && segments[0] == "bands" && segments[2] == "attachments" ->
                attachments.download(user, segments[1], segments[3])
            method == "GET" && segments.size == 2 && segments[0] == "invites" -> invites.preview(user, segments[1])
            method == "POST" && segments.size == 3 && segments[0] == "invites" && segments[2] == "accept" -> invites.accept(user, segments[1])
            else -> throw ApiError(404, ApiErrors.NOT_FOUND, "No route for ${request.method} ${request.path}")
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
        /**
         * The oldest app version (its version code: the run number of the CI run that deployed it; before 2026-10-25,
         * of the separate Deploy workflow's run) the server still serves; older apps show "update required".
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
