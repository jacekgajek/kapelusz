package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.SessionResponse
import com.jacekgajek.artsplit.model.ArtSplitJson
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
        return ArtSplitJson.decodeFromString(SessionResponse.serializer(), response.body!!)
    }

    @Test
    fun aGoogleTokenBuysASessionThatWorksAndRenews() {
        val first = session("google-alice")
        assertTrue(first.token.startsWith(SessionTokens.PREFIX))
        assertEquals(now + SessionTokens.LIFETIME_MS, first.expiresAt)
        val me = call("GET", "/me", first.token)
        assertEquals("alice", ArtSplitJson.decodeFromString(MeResponse.serializer(), me.body!!).userId)

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
}
