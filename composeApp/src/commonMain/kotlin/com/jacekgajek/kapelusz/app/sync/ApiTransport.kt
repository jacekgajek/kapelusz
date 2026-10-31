package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer

/**
 * What every request to the Kapelusz API shares: the address, what the app says about itself, how the server's
 * errors are read, and its [signals]. [ApiClient] sends with the user's token on top of it; [SessionApi] gets that
 * token, so it must not depend on it.
 */
class ApiTransport(
    private val http: HttpClient,
    private val baseUrl: String,
    /** Sent with every request (`appVersion`), so the server can tell an outdated app to update (I2 §2). */
    private val appVersion: Int = 0,
    /**
     * The Android installation's id, sent with every request (`installation`), so the server doesn't send this device a
     * sync message about its own changes (I7 §2.4); null on the web.
     */
    private val installation: String? = null,
) {
    private val _signals = MutableSharedFlow<ApiSignal>(extraBufferCapacity = 16)

    /** What the server told this app besides answers: it is outdated, or signed out on all devices. */
    val signals: SharedFlow<ApiSignal> = _signals.asSharedFlow()

    internal suspend fun attempt(
        method: HttpMethod,
        path: String,
        bearer: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = http.request("$baseUrl$path") {
        this.method = method
        // A query parameter, not a header: a new header would need the API's CORS allow-list changed.
        parameter(ApiErrors.APP_VERSION_PARAMETER, appVersion)
        installation?.let { parameter(ApiErrors.INSTALLATION_PARAMETER, it) }
        header(HttpHeaders.Authorization, "Bearer $bearer")
        configure()
    }

    internal suspend fun <T> parse(response: HttpResponse, serializer: KSerializer<T>): T {
        val body = text(response)
        return KapeluszJson.decodeFromString(serializer, body)
    }

    internal fun errorOf(body: String) = runCatching { KapeluszJson.decodeFromString(ErrorResponse.serializer(), body) }.getOrNull()

    internal suspend fun checkRevoked(code: String?) {
        if (code == ApiErrors.SESSIONS_REVOKED) {
            _signals.emit(ApiSignal.SessionsRevoked)
            throw SessionsRevokedException()
        }
    }

    /** The body of a successful response; otherwise the server's error as an [ApiException]. */
    internal suspend fun text(response: HttpResponse): String {
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val error = errorOf(text)
            if (error?.error == ApiErrors.UPDATE_REQUIRED) _signals.emit(ApiSignal.UpdateRequired)
            // E.g. exchanging a token for a session (which bypasses the token provider).
            checkRevoked(error?.error)
            throw ApiException(response.status.value, error?.error ?: "http_${response.status.value}", error?.message ?: text)
        }
        return text
    }
}

/** A signal from the server about this app as a whole, not an answer to one request. */
sealed interface ApiSignal {
    /** The server no longer serves this app version (I2 §2). */
    data object UpdateRequired : ApiSignal

    /** The user signed out on all devices (I3 R6); the request that learnt it threw [SessionsRevokedException]. */
    data object SessionsRevoked : ApiSignal
}
