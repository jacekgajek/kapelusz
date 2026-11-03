package com.jacekgajek.kapelusz.app.auth

import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.ApiSignal
import com.jacekgajek.kapelusz.app.sync.ApiTransport
import com.jacekgajek.kapelusz.app.sync.SessionApi
import com.jacekgajek.kapelusz.app.sync.SessionsRevokedException
import com.jacekgajek.kapelusz.history.KapeluszJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalEncodingApi::class)
fun fakeIdToken(sub: String, expSeconds: Long, email: String = "$sub@example.com"): String {
    val encode = { text: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray()) }
    return "${encode("""{"alg":"RS256"}""")}.${encode("""{"sub":"$sub","email":"$email","name":"Alice","exp":$expSeconds}""")}.signature"
}

class SessionRepositoryTest :
    ExpectSpec({
        isolationMode = IsolationMode.InstancePerRoot
        coroutineTestScope = true
        var now = 1_000_000_000L
        var refreshed: String? = null
        var refreshCalls = 0
        var googleSignOuts = 0
        val google = object : GoogleAuth {
            override suspend fun refresh(): String? = refreshed.also { refreshCalls++ }
            override suspend fun signOut() {
                googleSignOuts++
            }
        }

        expect("reads claims from id token") {
            val session = Session.fromIdToken(fakeIdToken("sub-1", 2_000))
            session.userId shouldBe "sub-1"
            session.email shouldBe "sub-1@example.com"
            session.expiresAt shouldBe 2_000_000L
        }

        expect("refreshes only near expiry and keeps the same user") {
            val store = InMemoryStore()
            val sessions = SessionRepository(store, google, clock = { now })
            val first = fakeIdToken("sub-1", now / 1000 + 3600)
            sessions.signedIn(first)
            sessions.validToken() shouldBe first
            refreshCalls shouldBe 0

            now += 58 * 60 * 1000
            refreshed = fakeIdToken("sub-1", now / 1000 + 3600)
            sessions.validToken() shouldBe refreshed
            refreshCalls shouldBe 1

            refreshed = fakeIdToken("someone-else", now / 1000 + 3600)
            withClue("a token of another account is not accepted") { sessions.validToken(forceRefresh = true).shouldBeNull() }

            val reloaded = SessionRepository(store, google, clock = { now })
            reloaded.load()
            reloaded.session.value?.userId shouldBe "sub-1"

            reloaded.signOut()
            reloaded.session.value.shouldBeNull()
            store.values[SessionRepository.KEY].shouldBeNull()
        }

        expect("keeps the current token until it really expires when renewal fails") {
            val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
            val token = fakeIdToken("sub-1", now / 1000 + 3 * 60) // expires in 3 minutes: inside the renewal margin
            sessions.signedIn(token)
            refreshed = null
            withClue("renewal failed, but the token still works") { sessions.validToken() shouldBe token }
            withClue("the server refused it: no fallback") { sessions.validToken(forceRefresh = true).shouldBeNull() }

            now += 3 * 60 * 1000
            sessions.validToken().shouldBeNull()
        }

        expect("reauthentication must be the same account") {
            val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
            sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 60))
            sessions.reauthenticated(fakeIdToken("sub-2", now / 1000 + 3600)) shouldBe false
            sessions.reauthenticated(fakeIdToken("sub-1", now / 1000 + 3600)) shouldBe true
            sessions.session.value!!.expiresAt shouldBe now + 3600 * 1000
        }

        expect("tries a background renewal only once in a while") {
            // Each API request asks for a token; on Android a renewal may show the account picker, so it must not repeat.
            val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
            val token = fakeIdToken("sub-1", now / 1000 + 4 * 60) // inside the renewal margin
            sessions.signedIn(token)
            refreshed = null
            repeat(5) { sessions.validToken() shouldBe token }
            withClue("one attempt, not one per request") { refreshCalls shouldBe 1 }

            withClue("a refused token is renewed right away") { sessions.validToken(forceRefresh = true).shouldBeNull() }
            refreshCalls shouldBe 2

            now += SessionRepository.REFRESH_RETRY_MS
            refreshed = fakeIdToken("sub-1", now / 1000 + 3600)
            sessions.validToken() shouldBe refreshed
            refreshCalls shouldBe 3
        }

        expect("a cached token is no renewal") {
            val sessions = SessionRepository(InMemoryStore(), google, clock = { now })
            val token = fakeIdToken("sub-1", now / 1000 + 4 * 60)
            sessions.signedIn(token)
            refreshed = token // Google handed back the same token
            sessions.validToken() shouldBe token
            sessions.validToken() shouldBe token
            refreshCalls shouldBe 1
        }

        val exchanges = mutableListOf<String>()
        var sessionServer: (String) -> SessionResponse = { bearer ->
            exchanges += bearer
            SessionResponse("as1.session-${exchanges.size}", now + 30L * 24 * 3600 * 1000)
        }

        fun withSessions(store: InMemoryStore = InMemoryStore()) = SessionRepository(
            store,
            google,
            clock = { now },
        ) { bearer -> sessionServer(bearer) }

        expect("uses a server session and needs Google only at sign-in") {
            val store = InMemoryStore()
            val sessions = withSessions(store)
            val google = fakeIdToken("sub-1", now / 1000 + 3600)
            sessions.signedIn(google)
            sessions.validToken() shouldBe "as1.session-1"
            withClue("the Google token bought the session") { exchanges shouldBe listOf(google) }

            // Hours later the Google token is long expired: the session carries on without asking Google.
            now += 5 * 3600 * 1000
            repeat(3) { sessions.validToken() shouldBe "as1.session-1" }
            refreshCalls shouldBe 0

            // After 10 days it is renewed with itself, still without Google.
            now += 11L * 24 * 3600 * 1000
            sessions.validToken() shouldBe "as1.session-2"
            exchanges.last() shouldBe "as1.session-1"
            refreshCalls shouldBe 0

            val reloaded = withSessions(store).apply { load() }
            withClue("kept across restarts") { reloaded.validToken() shouldBe "as1.session-2" }
        }

        expect("falls back to the Google token without sessions") {
            // An older server (no /session) or no network: requests keep using the Google token, as before.
            sessionServer = {
                exchanges += it
                throw IllegalStateException("404")
            }
            val sessions = withSessions()
            val google = fakeIdToken("sub-1", now / 1000 + 3600)
            sessions.signedIn(google)
            sessions.validToken() shouldBe google
            sessions.validToken() shouldBe google
            withClue("not retried on every request") { exchanges shouldHaveSize 1 }

            now += SessionRepository.REFRESH_RETRY_MS
            sessionServer = {
                exchanges += it
                SessionResponse("as1.late", now + 1000L * 24 * 3600 * 1000)
            }
            sessions.validToken() shouldBe "as1.late"
        }

        expect("a refused session is replaced using Google") {
            val sessions = withSessions()
            sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 3600))
            sessions.validToken() shouldBe "as1.session-1"
            // The server refused the session (e.g. its key changed); the Google token is still valid and buys a new one.
            sessions.validToken(forceRefresh = true) shouldBe "as1.session-2"
            refreshCalls shouldBe 0
        }

        // A 401 for a token another request already replaced keeps the new session.
        expect("a refusal of an old token keeps the renewed session") {
            val sessions = withSessions()
            sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 3600))
            sessions.validToken() shouldBe "as1.session-1"
            sessions.validToken(forceRefresh = true, refused = "as1.session-1") shouldBe "as1.session-2"
            // A request still in flight with session-1 is refused now: session-2 stays.
            sessions.validToken(forceRefresh = true, refused = "as1.session-1") shouldBe "as1.session-2"
            exchanges shouldHaveSize 2
        }

        // A server that refuses everything as "signed out on all devices", and the paths it was asked for.
        val revokedPaths = mutableListOf<String>()
        val revokedServer = HttpClient(
            MockEngine { request ->
                revokedPaths += request.url.encodedPath
                respond(
                    """{"error":"sessions_revoked","message":"Signed out on all devices"}""",
                    HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        // The app's wiring: requests use the repository's tokens, and a revoke signs out.
        fun revokedApi(store: InMemoryStore, scope: CoroutineScope): Pair<SessionRepository, ApiClient> {
            val transport = ApiTransport(revokedServer, "https://api.test")
            val sessions = SessionRepository(store, google, clock = { now }, exchange = SessionApi(transport)::createSession)
            val api = ApiClient(transport) { refused -> sessions.validToken(forceRefresh = refused != null, refused = refused) }
            // What the app's runtime does on the signal (through Account); here at once, where it is emitted.
            scope.launch(UnconfinedTestDispatcher()) {
                api.signals.collect { if (it is ApiSignal.SessionsRevoked) sessions.signOut() }
            }
            return sessions to api
        }

        // After "sign out on all devices" elsewhere, a refused session is not replaced behind the user's back.
        expect("a revoked session signs out without renewing") {
            val store = InMemoryStore()
            val (sessions, api) = revokedApi(store, backgroundScope)
            // Signed in earlier: a session from the server, and the Google token still fresh enough to buy another.
            store.write(
                SessionRepository.KEY,
                KapeluszJson.encodeToString(
                    Session.serializer(),
                    Session.fromIdToken(fakeIdToken("sub-1", now / 1000 + 3600))
                        .copy(apiToken = "as1.old", apiExpiresAt = now + 25L * 24 * 3600 * 1000),
                ),
            )
            sessions.load()

            shouldThrow<SessionsRevokedException> { api.me() }
            withClue("no exchange, no retry") { revokedPaths shouldBe listOf("/me") }
            withClue("Google is not asked") { refreshCalls shouldBe 0 }
            sessions.session.value.shouldBeNull()
            withClue("the Google token is gone too") { store.values[SessionRepository.KEY].shouldBeNull() }
            withClue("and Google will not sign in silently") { googleSignOuts shouldBe 1 }
        }

        expect("a revoked Google token is not used after the exchange fails") {
            val store = InMemoryStore()
            val (sessions, api) = revokedApi(store, backgroundScope)
            sessions.signedIn(fakeIdToken("sub-1", now / 1000 + 3600))

            shouldThrow<SessionsRevokedException> { api.me() }
            withClue("the request itself is not sent with the Google token") { revokedPaths shouldBe listOf("/session") }
            refreshCalls shouldBe 0
            sessions.session.value.shouldBeNull()
            store.values[SessionRepository.KEY].shouldBeNull()
        }
    })
