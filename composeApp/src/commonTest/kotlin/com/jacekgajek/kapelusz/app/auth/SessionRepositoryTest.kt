package com.jacekgajek.kapelusz.app.auth

import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
fun fakeIdToken(sub: String, expSeconds: Long, email: String = "$sub@example.com"): String {
    val encode = { text: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray()) }
    return "${encode("""{"alg":"RS256"}""")}.${encode("""{"sub":"$sub","email":"$email","name":"Alice","exp":$expSeconds}""")}.signature"
}

class SessionRepositoryTest {
    private var now = 1_000_000_000L
    private var refreshed: String? = null
    private var refreshCalls = 0
    private val google = object : GoogleAuth {
        override suspend fun refresh(): String? = refreshed.also { refreshCalls++ }
        override suspend fun signOut() {}
    }

    @Test
    fun readsClaimsFromIdToken() {
        val session = Session.fromIdToken(fakeIdToken("sub-1", 2_000))
        assertEquals("sub-1", session.userId)
        assertEquals("sub-1@example.com", session.email)
        assertEquals(2_000_000L, session.expiresAt)
    }

    @Test
    fun refreshesOnlyNearExpiryAndKeepsTheSameUser() = runTest {
        val store = InMemoryStore()
        val sessions = SessionRepository(store, google, clock = { now })
        val first = fakeIdToken("sub-1", now / 1000 + 3600)
        sessions.signedIn(first)
        assertEquals(first, sessions.validToken())
        assertEquals(0, refreshCalls)

        now += 58 * 60 * 1000
        refreshed = fakeIdToken("sub-1", now / 1000 + 3600)
        assertEquals(refreshed, sessions.validToken())
        assertEquals(1, refreshCalls)

        refreshed = fakeIdToken("someone-else", now / 1000 + 3600)
        assertNull(sessions.validToken(forceRefresh = true), "a token of another account is not accepted")

        val reloaded = SessionRepository(store, google, clock = { now })
        reloaded.load()
        assertEquals("sub-1", reloaded.session.value?.userId)

        reloaded.signOut()
        assertNull(reloaded.session.value)
        assertNull(store.values[SessionRepository.KEY])
    }

    @Test
    fun keepsTheCurrentTokenUntilItReallyExpiresWhenRenewalFails() = runTest {
        val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
        val token = fakeIdToken("sub-1", now / 1000 + 3 * 60) // expires in 3 minutes: inside the renewal margin
        sessions.signedIn(token)
        refreshed = null
        assertEquals(token, sessions.validToken(), "renewal failed, but the token still works")
        assertNull(sessions.validToken(forceRefresh = true), "the server refused it: no fallback")

        now += 3 * 60 * 1000
        assertNull(sessions.validToken())
    }

    @Test
    fun reauthenticationMustBeTheSameAccount() = runTest {
        val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
        sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 60))
        assertEquals(false, sessions.reauthenticated(fakeIdToken("sub-2", now / 1000 + 3600)))
        assertEquals(true, sessions.reauthenticated(fakeIdToken("sub-1", now / 1000 + 3600)))
        assertEquals(now + 3600 * 1000, sessions.session.value!!.expiresAt)
    }

    @Test
    fun triesABackgroundRenewalOnlyOnceInAWhile() = runTest {
        // Each API request asks for a token; on Android a renewal may show the account picker, so it must not repeat.
        val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
        val token = fakeIdToken("sub-1", now / 1000 + 4 * 60) // inside the renewal margin
        sessions.signedIn(token)
        refreshed = null
        repeat(5) { assertEquals(token, sessions.validToken()) }
        assertEquals(1, refreshCalls, "one attempt, not one per request")

        assertNull(sessions.validToken(forceRefresh = true), "a refused token is renewed right away")
        assertEquals(2, refreshCalls)

        now += SessionRepository.REFRESH_RETRY_MS
        refreshed = fakeIdToken("sub-1", now / 1000 + 3600)
        assertEquals(refreshed, sessions.validToken())
        assertEquals(3, refreshCalls)
    }

    @Test
    fun aCachedTokenIsNoRenewal() = runTest {
        val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
        val token = fakeIdToken("sub-1", now / 1000 + 4 * 60)
        sessions.signedIn(token)
        refreshed = token // Google handed back the same token
        assertEquals(token, sessions.validToken())
        assertEquals(token, sessions.validToken())
        assertEquals(1, refreshCalls)
    }

    private var exchanges = mutableListOf<String>()
    private var sessionServer: (String) -> SessionResponse = { bearer ->
        exchanges += bearer
        SessionResponse("as1.session-${exchanges.size}", now + 30L * 24 * 3600 * 1000)
    }
    private fun withSessions(store: InMemoryStore = InMemoryStore()) =
        SessionRepository(store, google, clock = { now }) { bearer -> sessionServer(bearer) }

    @Test
    fun usesAServerSessionAndNeedsGoogleOnlyAtSignIn() = runTest {
        val store = InMemoryStore()
        val sessions = withSessions(store)
        val google = fakeIdToken("sub-1", now / 1000 + 3600)
        sessions.signedIn(google)
        assertEquals("as1.session-1", sessions.validToken())
        assertEquals(listOf(google), exchanges, "the Google token bought the session")

        // Hours later the Google token is long expired: the session carries on without asking Google.
        now += 5 * 3600 * 1000
        repeat(3) { assertEquals("as1.session-1", sessions.validToken()) }
        assertEquals(0, refreshCalls)

        // After 10 days it is renewed with itself, still without Google.
        now += 11L * 24 * 3600 * 1000
        assertEquals("as1.session-2", sessions.validToken())
        assertEquals("as1.session-1", exchanges.last())
        assertEquals(0, refreshCalls)

        val reloaded = withSessions(store).apply { load() }
        assertEquals("as1.session-2", reloaded.validToken(), "kept across restarts")
    }

    @Test
    fun fallsBackToTheGoogleTokenWithoutSessions() = runTest {
        // An older server (no /session) or no network: requests keep using the Google token, as before.
        sessionServer = { exchanges += it; throw IllegalStateException("404") }
        val sessions = withSessions()
        val google = fakeIdToken("sub-1", now / 1000 + 3600)
        sessions.signedIn(google)
        assertEquals(google, sessions.validToken())
        assertEquals(google, sessions.validToken())
        assertEquals(1, exchanges.size, "not retried on every request")

        now += SessionRepository.REFRESH_RETRY_MS
        sessionServer = { exchanges += it; SessionResponse("as1.late", now + 1000L * 24 * 3600 * 1000) }
        assertEquals("as1.late", sessions.validToken())
    }

    @Test
    fun aRefusedSessionIsReplacedUsingGoogle() = runTest {
        val sessions = withSessions()
        sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 3600))
        assertEquals("as1.session-1", sessions.validToken())
        // The server refused the session (e.g. its key changed); the Google token is still valid and buys a new one.
        assertEquals("as1.session-2", sessions.validToken(forceRefresh = true))
        assertEquals(0, refreshCalls)
    }

    /** ITERATION_3 R9: a 401 for a token another request already replaced keeps the new session. */
    @Test
    fun aRefusalOfAnOldTokenKeepsTheRenewedSession() = runTest {
        val sessions = withSessions()
        sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 3600))
        assertEquals("as1.session-1", sessions.validToken())
        assertEquals("as1.session-2", sessions.validToken(forceRefresh = true, refused = "as1.session-1"))
        // A request still in flight with session-1 is refused now: session-2 stays.
        assertEquals("as1.session-2", sessions.validToken(forceRefresh = true, refused = "as1.session-1"))
        assertEquals(2, exchanges.size)
    }
}
