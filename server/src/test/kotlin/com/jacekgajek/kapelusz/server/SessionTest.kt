package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
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

    // "Google" accepts any token that looks like a Google one: "google-<sub>", or "google-<sub>@<issued at>".
    private val api = Api(store, { token ->
        token.removePrefix("google-").takeIf { token.startsWith("google-") }?.split('@')?.let { parts ->
            VerifiedUser(parts[0], "${parts[0]}@example.com", parts[0], issuedAt = parts.getOrNull(1)?.toLong())
        }
    }, clock = { now })

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
        assertNotNull(store.get(StorageKeys.SESSION_KEY))
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

    /** ITERATION_3 R6: "sign out on all devices" ends every session begun before it, for this user only. */
    @Test
    fun revokingEndsEverySessionOfTheUserButNotNewSignIns() {
        val phone = session("google-alice").token
        now += 1000
        val laptop = session("google-alice").token
        val bob = session("google-bob").token
        now += 1000

        assertEquals(204, call("POST", "/sessions/revoke", phone).status)
        assertEquals(401, call("GET", "/me", phone).status)
        assertEquals(401, call("GET", "/me", laptop).status)
        assertEquals(401, call("POST", "/session", laptop).status, "nor can it be renewed")
        assertEquals(200, call("GET", "/me", bob).status, "another user is not affected")

        // A Google sign-in in the same millisecond as the revoke starts a valid session.
        val fresh = session("google-alice").token
        assertEquals(200, call("GET", "/me", fresh).status)
        now += 1000
        assertEquals(200, call("GET", "/me", session(fresh).token).status, "and it renews")
    }

    /** A Google token issued before the revoke would let the other devices back in silently: refused, distinctly. */
    @Test
    fun aGoogleTokenIssuedBeforeTheRevokeIsRefused() {
        val issuedBefore = "google-alice@${now - 1000}"
        val phone = session(issuedBefore).token
        now += 1000
        assertEquals(204, call("POST", "/sessions/revoke", phone).status)

        for (refused in listOf(call("POST", "/session", issuedBefore), call("GET", "/me", issuedBefore), call("GET", "/me", phone))) {
            assertEquals(401, refused.status)
            assertEquals(ApiErrors.SESSIONS_REVOKED, KapeluszJson.decodeFromString(ErrorResponse.serializer(), refused.body!!).error)
        }
        // Anything else that is refused stays a plain "unauthorized".
        assertEquals(ApiErrors.UNAUTHORIZED, KapeluszJson.decodeFromString(ErrorResponse.serializer(), call("GET", "/me", "nonsense").body!!).error)

        now += 1000
        val issuedAfter = "google-alice@$now"
        assertEquals(200, call("GET", "/me", issuedAfter).status)
        assertEquals(200, call("GET", "/me", session(issuedAfter).token).status)
    }

    /** An unreadable user record must not lock its user out: it counts as never revoked. */
    @Test
    fun anUnreadableUserRecordDoesNotRefuseTheUser() {
        val token = session("google-alice").token
        store.put(StorageKeys.user("alice"), "{not json")
        // A fresh instance, which has not read the record yet. (A band of someone else: the check reads no user record.)
        val other = Api(store, { null }, clock = { now })
        assertEquals(404, other.handle(HttpRequest("GET", "/bands/nope/state", mapOf("authorization" to "Bearer $token"))).status)
    }

    @Test
    fun anotherInstanceLearnsOfTheRevokeWithinAMinute() {
        val token = session("google-alice").token
        val other = Api(store, { null }, clock = { now })
        assertEquals(200, other.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer $token"))).status)
        now += 1000
        assertEquals(204, call("POST", "/sessions/revoke", token).status)
        assertEquals(now, KapeluszJson.decodeFromString(UserRecord.serializer(), store.get(StorageKeys.user("alice"))!!.content).sessionsValidFrom)
        // The other instance read the time before the revoke and trusts it for up to a minute ...
        now += SessionRevocations.CACHE_MS - 1001
        assertEquals(200, other.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer $token"))).status)
        // ... then reads it again.
        now += 1
        assertEquals(401, other.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer $token"))).status)
    }
}
