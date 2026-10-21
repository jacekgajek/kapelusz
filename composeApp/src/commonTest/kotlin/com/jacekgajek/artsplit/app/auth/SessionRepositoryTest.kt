package com.jacekgajek.artsplit.app.auth

import com.jacekgajek.artsplit.app.data.InMemoryStore
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
        val sessions = SessionRepository(store, google) { now }
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

        val reloaded = SessionRepository(store, google) { now }
        reloaded.load()
        assertEquals("sub-1", reloaded.session.value?.userId)

        reloaded.signOut()
        assertNull(reloaded.session.value)
        assertNull(store.values[SessionRepository.KEY])
    }
}
