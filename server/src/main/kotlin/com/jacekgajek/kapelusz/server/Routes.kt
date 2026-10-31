package com.jacekgajek.kapelusz.server

import kotlinx.serialization.KSerializer

/**
 * The routes of [Api], written as paths: `bands/{bandId}/invites/{code}`. A `{…}` segment matches any one segment
 * and is handed to the route, in order (`{ (bandId, code) -> … }`); the others must match exactly. The first route
 * whose method and path match answers; [routes] returns null when none does.
 */
internal class Routes(private val request: HttpRequest) {
    private val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
    var response: HttpResponse? = null
        private set

    fun get(pattern: String, handle: (List<String>) -> HttpResponse) = route("GET", pattern, handle)
    fun post(pattern: String, handle: (List<String>) -> HttpResponse) = route("POST", pattern, handle)
    fun put(pattern: String, handle: (List<String>) -> HttpResponse) = route("PUT", pattern, handle)
    fun delete(pattern: String, handle: (List<String>) -> HttpResponse) = route("DELETE", pattern, handle)

    /** The request's JSON body; a missing or malformed one is a 400. */
    fun <T> body(serializer: KSerializer<T>): T = parse(request, serializer)

    private fun route(method: String, pattern: String, handle: (List<String>) -> HttpResponse) {
        if (response != null || method != request.method) return
        val parts = pattern.split('/')
        if (parts.size != segments.size) return
        val parameters = mutableListOf<String>()
        for ((part, segment) in parts.zip(segments)) {
            when {
                part.startsWith('{') -> parameters += segment
                part != segment -> return
            }
        }
        response = handle(parameters)
    }
}

internal inline fun routes(request: HttpRequest, define: Routes.() -> Unit): HttpResponse? = Routes(request).apply(define).response
