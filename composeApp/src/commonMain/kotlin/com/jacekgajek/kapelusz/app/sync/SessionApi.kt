package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.SessionResponse
import io.ktor.http.HttpMethod

/** The one request made without a session: getting one. Its own class, so the session store needs no [ApiClient]. */
class SessionApi(private val transport: ApiTransport) {
    /** A new Kapelusz session for [bearer] (a Google ID token or the current session). */
    suspend fun createSession(bearer: String): SessionResponse {
        val response = transport.attempt(HttpMethod.Post, "/session", bearer) {}
        return transport.parse(response, SessionResponse.serializer())
    }
}
