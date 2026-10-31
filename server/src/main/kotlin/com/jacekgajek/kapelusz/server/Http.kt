package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import java.net.URLDecoder

/** Transport-independent request; header names are lower-case. */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val query: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

data class HttpResponse(val status: Int, val body: String? = null, val headers: Map<String, String> = emptyMap())

/**
 * The query string's parameters, decoded; the same at both transport edges (the Lambda, the local server). A malformed
 * %-escape throws [IllegalArgumentException], which the edge answers with [malformedQuery].
 */
fun parseQuery(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }.associate {
    val (key, value) = it.split('=', limit = 2)
    URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
}

fun malformedQuery() = error(400, ApiErrors.BAD_REQUEST, "Malformed query string")

/** Something went wrong that no handler caught: logged where the edge can see it, said to the client without detail. */
fun unhandled(method: String, path: String, e: Exception): HttpResponse {
    System.err.println("Unhandled error for $method $path: ${e.stackTraceToString()}")
    return error(500, ApiErrors.INTERNAL, "Internal error")
}
