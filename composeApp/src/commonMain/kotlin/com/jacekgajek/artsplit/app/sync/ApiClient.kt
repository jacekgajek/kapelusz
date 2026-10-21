package com.jacekgajek.artsplit.app.sync

import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.ErrorResponse
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.ops.OpEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
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

/** The server answered with an error. */
class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message)

/** The user must sign in again: there is no token, or the server still refuses a refreshed one. */
class NeedsSignInException : RuntimeException("Sign in again")

/** Typed client for the Art Split API. [token] returns an ID token; `true` forces a refresh. */
class ApiClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val token: suspend (forceRefresh: Boolean) -> String?,
) {
    suspend fun me(): MeResponse = call(HttpMethod.Get, "/me", MeResponse.serializer())

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
            header(HttpHeaders.Authorization, "Bearer $bearer")
            configure()
        }

    private suspend fun <T> parse(response: HttpResponse, serializer: KSerializer<T>): T {
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val error = runCatching { ArtSplitJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
            throw ApiException(response.status.value, error?.error ?: "http_${response.status.value}", error?.message ?: text)
        }
        return ArtSplitJson.decodeFromString(serializer, text)
    }
}
