package com.jacekgajek.artsplit.app.sync

import com.jacekgajek.artsplit.api.AttachmentUploadRequest
import com.jacekgajek.artsplit.api.AttachmentUploadResponse
import com.jacekgajek.artsplit.api.AttachmentUrlResponse
import com.jacekgajek.artsplit.api.ApiErrors
import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.CreateInviteRequest
import com.jacekgajek.artsplit.api.ErrorResponse
import com.jacekgajek.artsplit.api.HistoryEntry
import com.jacekgajek.artsplit.api.InviteInfo
import com.jacekgajek.artsplit.api.InvitePreview
import com.jacekgajek.artsplit.api.InvitesResponse
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.api.SessionResponse
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.OpEnvelope
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** An operation in the history that this app version cannot read (made by a newer version). */
data class UnreadableOp(val opId: String, val eventId: String?)

/** A history entry as far as this app version can read it. */
data class HistoryItem(val entry: HistoryEntry, val unreadable: List<UnreadableOp> = emptyList())

data class HistoryPage(val month: String, val entries: List<HistoryItem>, val next: String?)

/** The server answered with an error. */
class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

/** The user must sign in again: there is no token, or the server still refuses a refreshed one. */
class NeedsSignInException : RuntimeException("Sign in again")

/** Typed client for the Art Split API. [token] returns an ID token; `true` forces a refresh. */
class ApiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    /** Sent with every request (`appVersion`), so the server can tell an outdated app to update (VERSION_2 §2). */
    private val appVersion: Int = 0,
    /** The server answered "update required". */
    private val onUpdateRequired: () -> Unit = {},
    private val token: suspend (forceRefresh: Boolean) -> String?,
) {
    suspend fun me(): MeResponse = call(HttpMethod.Get, "/me", MeResponse.serializer())

    /** A new Art Split session for [bearer] (a Google ID token or the current session); bypasses the token provider. */
    suspend fun createSession(bearer: String): SessionResponse =
        parse(attempt(HttpMethod.Post, "/session", bearer) {}, SessionResponse.serializer())

    suspend fun createBand(request: CreateBandRequest): BandStateResponse =
        call(HttpMethod.Post, "/bands", BandStateResponse.serializer(), body = ArtSplitJson.encodeToString(CreateBandRequest.serializer(), request))

    /** The band's state, or null when it has not changed since [knownVersion]. */
    suspend fun state(bandId: Id, knownVersion: String?): BandStateResponse? {
        val response = send(HttpMethod.Get, "/bands/$bandId/state") { if (knownVersion != null) header(HttpHeaders.IfNoneMatch, knownVersion) }
        if (response.status == HttpStatusCode.NotModified) return null
        return parse(response, BandStateResponse.serializer())
    }

    suspend fun push(bandId: Id, ops: List<OpEnvelope>): PushResponse =
        call(HttpMethod.Post, "/bands/$bandId/ops", PushResponse.serializer(), body = ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))

    /** Deletes the band for everyone (owner only). */
    suspend fun deleteBand(bandId: Id) {
        val response = send(HttpMethod.Delete, "/bands/$bandId") {}
        if (response.status.value !in 200..299) parse(response, ErrorResponse.serializer())
    }

    suspend fun invites(bandId: Id): InvitesResponse = call(HttpMethod.Get, "/bands/$bandId/invites", InvitesResponse.serializer())

    suspend fun createInvite(bandId: Id, memberId: Id, role: Role): InviteInfo = call(
        HttpMethod.Post, "/bands/$bandId/invites", InviteInfo.serializer(),
        body = ArtSplitJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(memberId, role)),
    )

    suspend fun revokeInvite(bandId: Id, code: String) {
        val response = send(HttpMethod.Delete, "/bands/$bandId/invites/$code") {}
        if (response.status.value !in 200..299) parse(response, ErrorResponse.serializer())
    }

    suspend fun previewInvite(code: String): InvitePreview = call(HttpMethod.Get, "/invites/$code", InvitePreview.serializer())

    suspend fun acceptInvite(code: String): BandStateResponse = call(HttpMethod.Post, "/invites/$code/accept", BandStateResponse.serializer())

    /**
     * One page of a month's history, newest first; [before] is the previous page's `next`. Read entry by entry and
     * operation by operation, so a change made by a newer app version (an operation this one does not know) shows
     * as such instead of failing the whole page.
     */
    suspend fun history(bandId: Id, month: String, before: String? = null): HistoryPage {
        val page = call(HttpMethod.Get, "/bands/$bandId/log?month=$month" + (before?.let { "&before=$it" } ?: ""), JsonObject.serializer())
        val entries = page["entries"]?.jsonArray.orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val ops = entry["ops"]?.jsonArray.orEmpty()
            val readable = ops.mapNotNull { op -> runCatching { ArtSplitJson.decodeFromJsonElement(OpEnvelope.serializer(), op) }.getOrNull() }
            val unreadable = ops.filter { op -> runCatching { ArtSplitJson.decodeFromJsonElement(OpEnvelope.serializer(), op) }.isFailure }
                .mapNotNull { it as? JsonObject }
                .map { op ->
                    UnreadableOp(
                        opId = op["opId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        eventId = (op["op"] as? JsonObject)?.get("eventId")?.jsonPrimitive?.contentOrNull,
                    )
                }
            val header = runCatching {
                HistoryEntry(
                    userId = entry.getValue("userId").jsonPrimitive.content,
                    memberId = entry["memberId"]?.jsonPrimitive?.contentOrNull,
                    at = entry.getValue("at").jsonPrimitive.long,
                    ops = readable,
                )
            }.getOrNull() ?: return@mapNotNull null
            HistoryItem(header, unreadable)
        }
        return HistoryPage(page["month"]?.jsonPrimitive?.contentOrNull ?: month, entries, page["next"]?.jsonPrimitive?.contentOrNull)
    }

    /** A URL to upload the file of an attachment the server already has. */
    suspend fun requestUpload(bandId: Id, costId: Id, attachmentId: Id): AttachmentUploadResponse = call(
        HttpMethod.Post, "/bands/$bandId/attachments", AttachmentUploadResponse.serializer(),
        body = ArtSplitJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest(costId, attachmentId)),
    )

    /** A short-lived URL to open an attachment. */
    suspend fun attachmentUrl(bandId: Id, attachmentId: Id): String =
        call(HttpMethod.Get, "/bands/$bandId/attachments/$attachmentId", AttachmentUrlResponse.serializer()).url

    private suspend fun <T> call(method: HttpMethod, path: String, serializer: KSerializer<T>, body: String? = null): T =
        parse(send(method, path) { if (body != null) setBody(TextContent(body, ContentType.Application.Json)) }, serializer)

    /** Sends with the current token; on 401 refreshes the token once and retries. */
    private suspend fun send(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit): HttpResponse {
        var response = attempt(method, path, token(false) ?: throw NeedsSignInException(), configure)
        if (response.status == HttpStatusCode.Unauthorized) {
            response = attempt(method, path, token(true) ?: throw NeedsSignInException(), configure)
            if (response.status == HttpStatusCode.Unauthorized) throw NeedsSignInException()
        }
        return response
    }

    private suspend fun attempt(method: HttpMethod, path: String, bearer: String, configure: HttpRequestBuilder.() -> Unit) =
        http.request("$baseUrl$path") {
            this.method = method
            // A query parameter, not a header: a new header would need the API's CORS allow-list changed.
            parameter(ApiErrors.APP_VERSION_PARAMETER, appVersion)
            header(HttpHeaders.Authorization, "Bearer $bearer")
            configure()
        }

    private suspend fun <T> parse(response: HttpResponse, serializer: KSerializer<T>): T {
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val error = runCatching { ArtSplitJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
            if (error?.error == ApiErrors.UPDATE_REQUIRED) onUpdateRequired()
            throw ApiException(response.status.value, error?.error ?: "http_${response.status.value}", error?.message ?: text)
        }
        return ArtSplitJson.decodeFromString(serializer, text)
    }
}
