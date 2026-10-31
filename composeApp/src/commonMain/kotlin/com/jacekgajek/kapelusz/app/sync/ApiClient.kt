package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.AttachmentUrlResponse
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.DeviceRegistration
import com.jacekgajek.kapelusz.api.DeviceRegistrationResponse
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.InvitePreview
import com.jacekgajek.kapelusz.api.InvitesResponse
import com.jacekgajek.kapelusz.api.LocationsResponse
import com.jacekgajek.kapelusz.api.MapLinkRequest
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PictureRequest
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** An operation in the history that this app version cannot read (made by a newer version). */
data class UnreadableOp(val opId: String, val eventId: String?)

/** One operation of a history entry, as far as this app version can read it. */
sealed interface ReadOp {
    data class Known(val envelope: OpEnvelope) : ReadOp
    data class Unknown(val op: UnreadableOp) : ReadOp
}

/** A history entry as far as this app version can read it; [ops] keeps the original order. */
data class HistoryItem(val entry: HistoryEntry, val ops: List<ReadOp>) {
    val unreadable: List<UnreadableOp> get() = ops.filterIsInstance<ReadOp.Unknown>().map { it.op }
}

data class HistoryPage(val month: String, val entries: List<HistoryItem>, val next: String?)

/** The server answered with an error. */
class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

/** The user must sign in again: there is no token, or the server still refuses a refreshed one. */
open class NeedsSignInException(message: String = "Sign in again") : RuntimeException(message)

/**
 * The user chose "sign out on all devices" (on this or another device): the server refuses every token from before,
 * Google's included. Nothing is renewed silently; the app signs out (ITERATION_3 R6).
 */
class SessionsRevokedException : NeedsSignInException("Signed out on all devices")

/** Typed client for the Kapelusz API, sending the user's session token; screens get its parts (ScreenApis.kt). */
class ApiClient(
    internal val transport: ApiTransport,
    /** The token to send; [refused] is the one the server just refused (null: the current one is wanted). */
    private val token: suspend (refused: String?) -> String?,
) : PlacesApi,
    InvitesApi,
    HistoryApi,
    CalendarApi,
    RawStateApi {
    /** Its own transport, for tests and tools; the app shares one with [SessionApi]. */
    constructor(
        http: HttpClient,
        baseUrl: String,
        appVersion: Int = 0,
        installation: String? = null,
        token: suspend (refused: String?) -> String?,
    ) : this(ApiTransport(http, baseUrl, appVersion, installation), token)

    /** The server's signals, see [ApiTransport.signals]. */
    val signals: SharedFlow<ApiSignal> get() = transport.signals

    suspend fun me(): MeResponse = call(HttpMethod.Get, "/me", MeResponse.serializer())

    /** This installation's Firebase token (ITERATION_7 §2.4); the answer says whether sync messages will come. */
    suspend fun registerDevice(installationId: String, token: String): Boolean = call(
        HttpMethod.Put,
        "/me/devices/$installationId",
        DeviceRegistrationResponse.serializer(),
        body = KapeluszJson.encodeToString(DeviceRegistration.serializer(), DeviceRegistration(token)),
    ).messaging

    /** No more sync messages for this installation (signing out). */
    suspend fun removeDevice(installationId: String) {
        transport.text(send(HttpMethod.Delete, "/me/devices/$installationId") {})
    }

    /** Hands the server the Google sign-in token this device kept, for the photo in it (ITERATION_2 §7.4). */
    suspend fun sendPicture(googleToken: String) {
        val body = KapeluszJson.encodeToString(PictureRequest.serializer(), PictureRequest(googleToken))
        transport.text(send(HttpMethod.Post, "/me/picture") { setBody(TextContent(body, ContentType.Application.Json)) })
    }

    /** The user's private calendar feed link (created on first use). */
    override suspend fun calendar(): CalendarSubscription = call(HttpMethod.Get, "/me/calendar", CalendarSubscription.serializer())

    override suspend fun changeCalendar(change: CalendarSubscriptionChange): CalendarSubscription = call(
        HttpMethod.Post,
        "/me/calendar",
        CalendarSubscription.serializer(),
        body = KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), change),
    )

    /** "Sign out on all devices" (ITERATION_3 R6): the server refuses every session of the user begun before now. */
    suspend fun revokeSessions() {
        transport.text(send(HttpMethod.Post, "/sessions/revoke") {})
    }

    suspend fun createBand(request: CreateBandRequest): BandStateResponse = call(HttpMethod.Post, "/bands", BandStateResponse.serializer(), body = KapeluszJson.encodeToString(CreateBandRequest.serializer(), request))

    /** The band's state, or null when it has not changed since [knownVersion]. */
    suspend fun state(bandId: Id, knownVersion: String?): BandStateResponse? {
        val response = send(HttpMethod.Get, "/bands/$bandId/state") { if (knownVersion != null) header(HttpHeaders.IfNoneMatch, knownVersion) }
        if (response.status == HttpStatusCode.NotModified) return null
        return transport.parse(response, BandStateResponse.serializer())
    }

    suspend fun push(bandId: Id, ops: List<OpEnvelope>): PushResponse = call(HttpMethod.Post, "/bands/$bandId/ops", PushResponse.serializer(), body = KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))

    /** Deletes the band for everyone (owner only). */
    suspend fun deleteBand(bandId: Id) {
        transport.text(send(HttpMethod.Delete, "/bands/$bandId") {})
    }

    /** Unlinks the caller's account from the band (not its owner, who gets [ApiErrors.OWNER_CANNOT_LEAVE]). */
    suspend fun leaveBand(bandId: Id) {
        transport.text(send(HttpMethod.Post, "/bands/$bandId/leave") {})
    }

    /** The band's file exactly as stored on the server, for leaders (ITERATION_2 §7.1). */
    override suspend fun rawState(bandId: Id): String = transport.text(send(HttpMethod.Get, "/bands/$bandId/raw") {})

    /** Places Google suggests for what was typed in Location (ITERATION_5 §9), in [language]; for leads up. */
    override suspend fun places(bandId: Id, query: String, language: String, session: String): List<PlaceSuggestion> = transport.parse(
        send(HttpMethod.Get, "/bands/$bandId/places") {
            parameter("q", query)
            parameter("lang", language)
            parameter("session", session)
        },
        PlacesResponse.serializer(),
    ).places

    /** Where the band's event locations are, for the "Where we played" map (ITERATION_7 §8); for any member. */
    suspend fun locations(bandId: Id): LocationsResponse {
        val response = send(HttpMethod.Get, "/bands/$bandId/locations") {}
        return transport.parse(response, LocationsResponse.serializer())
    }

    /** What Location becomes for a picked place: its name and full address (ITERATION_5 §9.1), ending the [session]. */
    override suspend fun placeText(bandId: Id, placeId: String, language: String, session: String): String = transport.parse(
        send(HttpMethod.Get, "/bands/$bandId/places/${placeId.encodeURLPathPart()}") {
            parameter("lang", language)
            parameter("session", session)
        },
        PlaceText.serializer(),
    ).text

    /** What Location becomes for a Google Maps link: the place and its address, or a pin's coordinates (ITERATION_5 §9.1). */
    override suspend fun resolveMapLink(bandId: Id, url: String, language: String): String = transport.parse(
        send(HttpMethod.Post, "/bands/$bandId/places/resolve") {
            parameter("lang", language)
            setBody(TextContent(KapeluszJson.encodeToString(MapLinkRequest.serializer(), MapLinkRequest(url)), ContentType.Application.Json))
        },
        PlaceText.serializer(),
    ).text

    override suspend fun invites(bandId: Id): InvitesResponse = call(HttpMethod.Get, "/bands/$bandId/invites", InvitesResponse.serializer())

    override suspend fun createInvite(bandId: Id, memberId: Id, role: Role): InviteInfo = call(
        HttpMethod.Post,
        "/bands/$bandId/invites",
        InviteInfo.serializer(),
        body = KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(memberId, role)),
    )

    override suspend fun revokeInvite(bandId: Id, code: String) {
        transport.text(send(HttpMethod.Delete, "/bands/$bandId/invites/$code") {})
    }

    suspend fun previewInvite(code: String): InvitePreview = call(HttpMethod.Get, "/invites/$code", InvitePreview.serializer())

    suspend fun acceptInvite(code: String): BandStateResponse = call(HttpMethod.Post, "/invites/$code/accept", BandStateResponse.serializer())

    /**
     * One page of a month's history, newest first; [before] is the previous page's `next`. With [months] above 1, the
     * first page of that many months back from [month], in one request (no `next`). Read leniently by
     * [HistoryDecoder], so a change made by a newer app version does not fail the whole page.
     */
    override suspend fun history(bandId: Id, month: String, before: String?, months: Int): HistoryPage {
        val query = "month=$month" + (before?.let { "&before=$it" } ?: "") + (if (months > 1) "&months=$months" else "")
        return HistoryDecoder.page(call(HttpMethod.Get, "/bands/$bandId/log?$query", JsonObject.serializer()), month)
    }

    /** A URL to upload the file of an attachment the server already has. */
    suspend fun requestUpload(bandId: Id, attachmentId: Id): AttachmentUploadResponse = call(
        HttpMethod.Post,
        "/bands/$bandId/attachments",
        AttachmentUploadResponse.serializer(),
        body = KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest(attachmentId)),
    )

    /** A short-lived URL to open an attachment. */
    suspend fun attachmentUrl(bandId: Id, attachmentId: Id): String = call(HttpMethod.Get, "/bands/$bandId/attachments/$attachmentId", AttachmentUrlResponse.serializer()).url

    private suspend fun <T> call(method: HttpMethod, path: String, serializer: KSerializer<T>, body: String? = null): T {
        val response = send(method, path) { if (body != null) setBody(TextContent(body, ContentType.Application.Json)) }
        return transport.parse(response, serializer)
    }

    /**
     * Sends with the current token; on 401 refreshes the token once and retries. Not after "signed out on all
     * devices": a refresh would sign this device in again behind the user's back.
     */
    private suspend fun send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit): HttpResponse {
        val bearer = token(null) ?: throw NeedsSignInException()
        var response = transport.attempt(method, path, bearer, configure)
        if (response.status == HttpStatusCode.Unauthorized) {
            transport.checkRevoked(transport.errorOf(response.bodyAsText())?.error)
            response = transport.attempt(method, path, token(bearer) ?: throw NeedsSignInException(), configure)
            if (response.status == HttpStatusCode.Unauthorized) {
                transport.checkRevoked(transport.errorOf(response.bodyAsText())?.error)
                throw NeedsSignInException()
            }
        }
        return response
    }
}
