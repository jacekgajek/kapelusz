package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.PictureRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.AttachmentUrlResponse
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.InvitePreview
import com.jacekgajek.kapelusz.api.InvitesResponse
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.OpEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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

/** Typed client for the Kapelusz API. [token] returns an ID token; `true` forces a refresh. */
class ApiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    /** Sent with every request (`appVersion`), so the server can tell an outdated app to update (ITERATION_2 §2). */
    private val appVersion: Int = 0,
    /** The server answered "update required". */
    private val onUpdateRequired: () -> Unit = {},
    /** The server answered "signed out on all devices"; then a [SessionsRevokedException] is thrown. */
    private val onSessionsRevoked: suspend () -> Unit = {},
    /** The token to send; [refused] is the one the server just refused (null: the current one is wanted). */
    private val token: suspend (refused: String?) -> String?,
) {
    suspend fun me(): MeResponse = call(HttpMethod.Get, "/me", MeResponse.serializer())

    /** Hands the server the Google sign-in token this device kept, for the photo in it (ITERATION_2 §7.4). */
    suspend fun sendPicture(googleToken: String) {
        text(send(HttpMethod.Post, "/me/picture") { setBody(TextContent(KapeluszJson.encodeToString(PictureRequest.serializer(), PictureRequest(googleToken)), ContentType.Application.Json)) })
    }

    /** The user's private calendar feed link (created on first use). */
    suspend fun calendar(): CalendarSubscription = call(HttpMethod.Get, "/me/calendar", CalendarSubscription.serializer())

    suspend fun changeCalendar(change: CalendarSubscriptionChange): CalendarSubscription = call(
        HttpMethod.Post, "/me/calendar", CalendarSubscription.serializer(),
        body = KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), change),
    )

    /** A new Kapelusz session for [bearer] (a Google ID token or the current session); bypasses the token provider. */
    suspend fun createSession(bearer: String): SessionResponse =
        parse(attempt(HttpMethod.Post, "/session", bearer) {}, SessionResponse.serializer())

    /** "Sign out on all devices" (ITERATION_3 R6): the server refuses every session of the user begun before now. */
    suspend fun revokeSessions() {
        text(send(HttpMethod.Post, "/sessions/revoke") {})
    }

    suspend fun createBand(request: CreateBandRequest): BandStateResponse =
        call(HttpMethod.Post, "/bands", BandStateResponse.serializer(), body = KapeluszJson.encodeToString(CreateBandRequest.serializer(), request))

    /** The band's state, or null when it has not changed since [knownVersion]. */
    suspend fun state(bandId: Id, knownVersion: String?): BandStateResponse? {
        val response = send(HttpMethod.Get, "/bands/$bandId/state") { if (knownVersion != null) header(HttpHeaders.IfNoneMatch, knownVersion) }
        if (response.status == HttpStatusCode.NotModified) return null
        return parse(response, BandStateResponse.serializer())
    }

    suspend fun push(bandId: Id, ops: List<OpEnvelope>): PushResponse =
        call(HttpMethod.Post, "/bands/$bandId/ops", PushResponse.serializer(), body = KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))

    /** Deletes the band for everyone (owner only). */
    suspend fun deleteBand(bandId: Id) {
        val response = send(HttpMethod.Delete, "/bands/$bandId") {}
        if (response.status.value !in 200..299) parse(response, ErrorResponse.serializer())
    }

    /** The band's file exactly as stored on the server, for admins (ITERATION_2 §7.1). */
    suspend fun rawState(bandId: Id): String = text(send(HttpMethod.Get, "/bands/$bandId/raw") {})

    suspend fun invites(bandId: Id): InvitesResponse = call(HttpMethod.Get, "/bands/$bandId/invites", InvitesResponse.serializer())

    suspend fun createInvite(bandId: Id, memberId: Id, role: Role): InviteInfo = call(
        HttpMethod.Post, "/bands/$bandId/invites", InviteInfo.serializer(),
        body = KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(memberId, role)),
    )

    suspend fun revokeInvite(bandId: Id, code: String) {
        val response = send(HttpMethod.Delete, "/bands/$bandId/invites/$code") {}
        if (response.status.value !in 200..299) parse(response, ErrorResponse.serializer())
    }

    suspend fun previewInvite(code: String): InvitePreview = call(HttpMethod.Get, "/invites/$code", InvitePreview.serializer())

    suspend fun acceptInvite(code: String): BandStateResponse = call(HttpMethod.Post, "/invites/$code/accept", BandStateResponse.serializer())

    /**
     * One page of a month's history, newest first; [before] is the previous page's `next`. Read leniently by
     * [HistoryDecoder], so a change made by a newer app version does not fail the whole page.
     */
    suspend fun history(bandId: Id, month: String, before: String? = null): HistoryPage =
        HistoryDecoder.page(call(HttpMethod.Get, "/bands/$bandId/log?month=$month" + (before?.let { "&before=$it" } ?: ""), JsonObject.serializer()), month)

    /** A URL to upload the file of an attachment the server already has. */
    suspend fun requestUpload(bandId: Id, attachmentId: Id): AttachmentUploadResponse = call(
        HttpMethod.Post, "/bands/$bandId/attachments", AttachmentUploadResponse.serializer(),
        body = KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest(attachmentId)),
    )

    /** A short-lived URL to open an attachment. */
    suspend fun attachmentUrl(bandId: Id, attachmentId: Id): String =
        call(HttpMethod.Get, "/bands/$bandId/attachments/$attachmentId", AttachmentUrlResponse.serializer()).url

    private suspend fun <T> call(method: HttpMethod, path: String, serializer: KSerializer<T>, body: String? = null): T =
        parse(send(method, path) { if (body != null) setBody(TextContent(body, ContentType.Application.Json)) }, serializer)

    /**
     * Sends with the current token; on 401 refreshes the token once and retries. Not after "signed out on all
     * devices": a refresh would sign this device in again behind the user's back.
     */
    private suspend fun send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit): HttpResponse {
        val bearer = token(null) ?: throw NeedsSignInException()
        var response = attempt(method, path, bearer, configure)
        if (response.status == HttpStatusCode.Unauthorized) {
            checkRevoked(errorOf(response.bodyAsText())?.error)
            response = attempt(method, path, token(bearer) ?: throw NeedsSignInException(), configure)
            if (response.status == HttpStatusCode.Unauthorized) {
                checkRevoked(errorOf(response.bodyAsText())?.error)
                throw NeedsSignInException()
            }
        }
        return response
    }

    private suspend fun checkRevoked(code: String?) {
        if (code == ApiErrors.SESSIONS_REVOKED) {
            onSessionsRevoked()
            throw SessionsRevokedException()
        }
    }

    private suspend fun attempt(method: HttpMethod, path: String, bearer: String, configure: HttpRequestBuilder.() -> Unit) =
        http.request("$baseUrl$path") {
            this.method = method
            // A query parameter, not a header: a new header would need the API's CORS allow-list changed.
            parameter(ApiErrors.APP_VERSION_PARAMETER, appVersion)
            header(HttpHeaders.Authorization, "Bearer $bearer")
            configure()
        }

    private suspend fun <T> parse(response: HttpResponse, serializer: KSerializer<T>): T = KapeluszJson.decodeFromString(serializer, text(response))

    private fun errorOf(body: String) = runCatching { KapeluszJson.decodeFromString(ErrorResponse.serializer(), body) }.getOrNull()

    /** The body of a successful response; otherwise the server's error as an [ApiException]. */
    private suspend fun text(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val error = errorOf(text)
            if (error?.error == ApiErrors.UPDATE_REQUIRED) onUpdateRequired()
            // E.g. exchanging a token for a session (which bypasses [send]).
            checkRevoked(error?.error)
            throw ApiException(response.status.value, error?.error ?: "http_${response.status.value}", error?.message ?: text)
        }
        return text
    }
}
