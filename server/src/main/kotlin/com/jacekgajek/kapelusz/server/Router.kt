package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.devices.SenderDevice
import kotlinx.serialization.KSerializer

/**
 * One request to the signed-in API, as a handler sees it: the request, who made it, the `{name}` segments of the
 * route that matched, and the device to leave out of the sync message. Built once per request by [Api].
 */
internal class Call(
    val request: HttpRequest,
    val user: VerifiedUser,
    /** The route's `{name}` segments, by name. */
    val params: Map<String, String>,
    /** The Android device making a change: no sync message for it about its own change. */
    val installation: SenderDevice?,
) {
    /** A `{name}` segment of the route; asking for one the route has not is a programming error. */
    fun param(name: String): String = params[name] ?: kotlin.error("The route has no {$name}")

    fun query(name: String): String? = request.query[name]

    fun header(name: String): String? = request.header(name)

    /** The request's JSON body; a missing or malformed one is a 400. */
    fun <T> body(serializer: KSerializer<T>): T = parse(request, serializer)
}

internal typealias RouteHandler = (Call) -> HttpResponse

/**
 * A route of the signed-in API: a [method] and a [pattern] of segments, `bands/{bandId}/invites/{code}`, where a
 * `{name}` segment matches any one segment and names it for [Call.param]; the others must match exactly.
 */
internal class Route(val method: String, val pattern: String, val handler: RouteHandler) {
    private val parts = pattern.trim('/').split('/')

    /** The named segments when [method] and [segments] match this route, else null. */
    fun match(method: String, segments: List<String>): Map<String, String>? {
        if (method != this.method || parts.size != segments.size) return null
        val params = mutableMapOf<String, String>()
        for ((part, segment) in parts.zip(segments)) {
            when {
                part.startsWith('{') -> params[part.trim('{', '}')] = segment
                part != segment -> return null
            }
        }
        return params
    }
}

/** The routes of [Api] in order: the first whose method and path match answers. */
internal class Router(private val routes: List<Route>) {
    /** The matching route with its named segments; null when none matches. */
    fun match(request: HttpRequest): Pair<Route, Map<String, String>>? {
        val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        for (route in routes) {
            val params = route.match(request.method, segments) ?: continue
            return route to params
        }
        return null
    }

    /** Answers [request] by its route as [call] sees it, or null when no route matches. */
    fun handle(request: HttpRequest, call: (Map<String, String>) -> Call): HttpResponse? {
        val (route, params) = match(request) ?: return null
        return route.handler(call(params))
    }
}
