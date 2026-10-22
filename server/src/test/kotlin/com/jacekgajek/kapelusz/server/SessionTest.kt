package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val store = InMemoryBlobStore()
    private var now = 1_790_000_000_000

    // "Google" accepts any token that looks like a Google one: "google-<sub>".
    private val api = Api(store, { token -> token.removePrefix("google-").takeIf { token.startsWith("google-") }?.let { VerifiedUser(it, "$it@example.com", it) } }, clock = { now })

    private fun call(method: String, path: String, bearer: String) = api.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $bearer")))

    private fun session(bearer: String): SessionResponse {
        val response = call("POST", "/session", bearer)
        assertEquals(200, response.status, response.body)
        return KapeluszJson.decodeFromString(SessionResponse.serializer(), response.body!!)
    }

    @Test
    fun aGoogleTokenBuysASessionThatWorksAndRenews() {
        val first = session("google-alice")
        assertTrue(first.token.startsWith(SessionTokens.PREFIX))
        assertEquals(now + SessionTokens.LIFETIME_MS, first.expiresAt)
        val me = call("GET", "/me", first.token)
        assertEquals("alice", KapeluszJson.decodeFromString(MeResponse.serializer(), me.body!!).userId)

        now += 1000
        val renewed = session(first.token)
        assertNotEquals(first.token, renewed.token)
        assertEquals(now + SessionTokens.LIFETIME_MS, renewed.expiresAt)
    }

    @Test
    fun expiredOrTamperedSessionsAreRefused() {
        val token = session("google-alice").token
        val (payload, mac) = token.removePrefix(SessionTokens.PREFIX).split('.')
        val forged = SessionTokens.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(java.util.Base64.getUrlDecoder().decode(payload).decodeToString().replace("alice", "mallo").toByteArray()) + ".$mac"
        assertEquals(401, call("GET", "/me", forged).status)
        assertEquals(401, call("GET", "/me", "${SessionTokens.PREFIX}garbage").status)

        now += SessionTokens.LIFETIME_MS
        assertEquals(401, call("GET", "/me", token).status)
    }

    @Test
    fun theKeyIsCreatedOnceAndShared() {
        val token = session("google-alice").token
        assertNotNull(store.get(SessionTokens.KEY_PATH))
        // Another Lambda instance (a fresh SessionTokens) reads the same key and accepts the token.
        assertEquals("alice", SessionTokens(store) { now }.verify(token)?.userId)
        assertNull(SessionTokens(InMemoryBlobStore()) { now }.verify(token), "a different key does not")
    }

    @Test
    fun renewalsKeepASessionAliveAtMost90DaysAfterTheGoogleSignIn() {
        var token = session("google-alice").token
        // Renewed every 25 days, it lives on until 90 days after the sign-in ...
        repeat(3) {
            now += 25L * 24 * 3600 * 1000
            token = session(token).token
        }
        // ... the last token ends with the session, not 30 days after its renewal ...
        now += 14L * 24 * 3600 * 1000
        assertEquals(200, call("GET", "/me", token).status, "day 89")
        // ... then the token and its renewal are refused (the app asks Google again).
        now += 2L * 24 * 3600 * 1000
        assertEquals(401, call("GET", "/me", token).status, "day 91")
        assertEquals(401, call("POST", "/session", token).status)
        assertEquals(200, call("POST", "/session", "google-alice").status, "a fresh Google sign-in starts a new chain")
    }
}
