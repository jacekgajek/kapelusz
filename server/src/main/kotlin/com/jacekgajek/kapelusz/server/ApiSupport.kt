package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer

/** An error answer: thrown anywhere in a handler, turned into [error] by [Api.handle]. [code] is one of [ApiErrors]. */
internal class ApiError(val status: Int, val code: String, message: String) : RuntimeException(message)

internal fun busy(message: String) = ApiError(409, ApiErrors.BUSY, message)

internal fun badRequestIf(condition: Boolean, message: () -> String) {
    if (condition) throw ApiError(400, ApiErrors.BAD_REQUEST, message())
}

/**
 * The read–modify–conditional-write loop (ITERATION_3 R12): runs [attempt] (which reads, then writes with `ifMatch`
 * or `ifNoneMatch`) again whenever a write finds the data changed meanwhile, at most [Api.MAX_ATTEMPTS] times; then
 * gives up with [exhausted]. A write that should not be retried must not throw [PreconditionFailed] out of [attempt].
 */
internal inline fun <T> retryOnConflict(exhausted: () -> T, attempt: () -> T): T {
    repeat(Api.MAX_ATTEMPTS) {
        try {
            return attempt()
        } catch (e: PreconditionFailed) {
            // changed meanwhile: read again
        }
    }
    return exhausted()
}

private val JSON = mapOf("content-type" to "application/json")

internal fun <T> ok(serializer: KSerializer<T>, value: T, headers: Map<String, String> = emptyMap()) = HttpResponse(200, encode(serializer, value), headers + JSON)

internal fun error(status: Int, code: String, message: String) = HttpResponse(status, encode(ErrorResponse.serializer(), ErrorResponse(code, message)), JSON)

internal fun <T> parse(request: HttpRequest, serializer: KSerializer<T>): T {
    val body = request.body ?: throw ApiError(400, ApiErrors.BAD_REQUEST, "Missing body")
    return try {
        decode(serializer, body)
    } catch (e: StackOverflowError) {
        // E.g. an undo of an undo of … nested thousands deep.
        throw ApiError(400, ApiErrors.BAD_REQUEST, "Too deeply nested")
    }
}

internal fun <T> encode(serializer: KSerializer<T>, value: T) = KapeluszJson.encodeToString(serializer, value)
internal fun <T> decode(serializer: KSerializer<T>, text: String) = KapeluszJson.decodeFromString(serializer, text)

/** The UTC month (`yyyy-MM`) of [millis]: history is stored per month. */
internal fun monthOf(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millis))

/** Size in UTF-8 bytes: what storage and the response limit count, unlike [String.length] (V5-M1 A2). */
internal fun String.utf8Size(): Int = encodeToByteArray().size
