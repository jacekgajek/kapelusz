package com.jacekgajek.kapelusz.app.auth

import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.app.data.KeyValueStore
import com.jacekgajek.kapelusz.app.sync.SessionsRevokedException
import kotlinx.coroutines.CancellationException
import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The signed-in Google user and their current ID token. */
@Serializable
data class Session(
    val userId: String,
    val email: String? = null,
    val name: String? = null,
    val idToken: String,
    /** Epoch millis when [idToken] expires. */
    val expiresAt: Long,
    /** Kapelusz's own session token (30 days, renewed while used); null until the server issued one. */
    val apiToken: String? = null,
    val apiExpiresAt: Long = 0,
) {
    companion object {
        /** Reads the claims of a Google ID token. The server verifies the signature; the app only needs the content. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromIdToken(idToken: String): Session {
            val payload = idToken.split('.').getOrNull(1) ?: error("Not a JWT")
            val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
            val claims = KapeluszJson.parseToJsonElement(json).jsonObject
            return Session(
                userId = claims.getValue("sub").jsonPrimitive.content,
                email = claims["email"]?.jsonPrimitive?.content,
                name = claims["name"]?.jsonPrimitive?.content,
                idToken = idToken,
                expiresAt = (claims["exp"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000,
            )
        }
    }
}

/** Platform side of Google sign-in that works without the user tapping anything. */
interface GoogleAuth {
    /** A fresh ID token for the already signed-in user, or null if that needs user interaction. */
    suspend fun refresh(): String?

    suspend fun signOut()
}

/**
 * Keeps the session and persists it. API requests use Kapelusz's own session token, which the app renews while it
 * is used; Google is asked only when there is no usable session (first sign-in, or one unused for a month), because
 * renewing Google's one-hour token can show its account picker on Android. [exchange] trades a bearer (a Google ID
 * token or the current session token) for a new session.
 */
class SessionRepository(
    private val store: KeyValueStore,
    private val google: GoogleAuth,
    private val clock: () -> Long,
    private val exchange: suspend (bearer: String) -> SessionResponse = { error("No session endpoint") },
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val refreshLock = Mutex()

    /** When a Google renewal was last attempted without the server having refused the token. */
    private var lastBackgroundRefresh: Long? = null

    /** When getting or renewing a session last failed (offline, or a server without sessions). */
    private var lastExchangeFailure: Long? = null

    suspend fun load() {
        _session.value = store.read(KEY)?.let { raw -> runCatching { KapeluszJson.decodeFromString(Session.serializer(), raw) }.getOrNull() }
    }

    suspend fun signedIn(idToken: String): Session {
        val session = Session.fromIdToken(idToken)
        save(session)
        return session
    }

    /**
     * The bearer token for the next request. [forceRefresh]: the server refused the last one. Null when the user has
     * to sign in again (the session itself is kept, so the app keeps working offline).
     */
    suspend fun validToken(forceRefresh: Boolean = false, refused: String? = null): String? = refreshLock.withLock {
        var current = _session.value ?: return null
        // Another request renewed the session meanwhile (ITERATION_3 R9): the refused token was the old one, keep the new.
        if (forceRefresh && refused != null && current.apiToken != null && current.apiToken != refused) return@withLock current.apiToken
        val now = clock()
        val session = current.apiToken?.takeIf { current.apiExpiresAt - now > EXPIRY_SKEW_MS }
        if (session != null && !forceRefresh) {
            if (current.apiExpiresAt - now < SESSION_RENEW_WINDOW_MS && exchangeAllowed(now)) {
                exchangeFor(current, session)?.let { return it }
            }
            return session
        }
        // A refused session is dropped; the Google token is then still worth a try (the server did not refuse it).
        val refusedGoogleToken = forceRefresh && current.apiToken == null
        if (current.apiToken != null) {
            current = current.copy(apiToken = null, apiExpiresAt = 0)
            save(current)
        }
        val googleToken = googleToken(current, refusedGoogleToken) ?: return null
        if (!exchangeAllowed(now)) return googleToken
        // Without a session (offline, or a server from before sessions) the Google token itself still works.
        exchangeFor(_session.value ?: return null, googleToken) ?: googleToken
    }

    private fun exchangeAllowed(now: Long) = lastExchangeFailure?.let { now - it >= REFRESH_RETRY_MS } ?: true

    /** A new session for [bearer], saved into [current]; null (and no retry for a while) if that failed. */
    private suspend fun exchangeFor(current: Session, bearer: String): String? = try {
        val response = exchange(bearer)
        lastExchangeFailure = null
        save(current.copy(apiToken = response.token, apiExpiresAt = response.expiresAt))
        response.token
    } catch (e: CancellationException) {
        throw e
    } catch (e: SessionsRevokedException) {
        // Signed out on all devices: no falling back to the Google token, which the server refuses too.
        throw e
    } catch (e: Throwable) {
        // Throwable: on the web, network errors are JsException.
        lastExchangeFailure = clock()
        null
    }

    /** A Google ID token valid for a few more minutes, renewed silently if needed and allowed; null if none. */
    private suspend fun googleToken(current: Session, refused: Boolean): String? {
        if (!refused && current.expiresAt - clock() > REFRESH_MARGIN_MS) return current.idToken
        // Without a fresh token, the current one is still fine until it actually expires (unless the server refused it).
        val stillValid = current.idToken.takeIf { !refused && current.expiresAt - clock() > EXPIRY_SKEW_MS }
        // On Android a renewal that cannot auto-select shows Google's account picker: background renewals are tried
        // once per REFRESH_RETRY_MS; only a token the server just refused is renewed right away.
        val now = clock()
        if (!refused && lastBackgroundRefresh?.let { now - it < REFRESH_RETRY_MS } == true) return stillValid
        if (!refused) lastBackgroundRefresh = now
        val fresh = runSuspendCatching { google.refresh() }.getOrNull() ?: return stillValid
        val refreshed = runCatching { Session.fromIdToken(fresh) }.getOrNull() ?: return stillValid
        if (refreshed.userId != current.userId) return stillValid
        // Google may hand back its cached token: that is no renewal (and saving it would ask again right away).
        if (refreshed.expiresAt <= current.expiresAt) return stillValid
        save(refreshed)
        return refreshed.idToken
    }

    /**
     * After the user signed in again interactively (e.g. from the "Sign in again" dialog). Must be the same account,
     * because this device's data belongs to it. Returns false for a different account.
     */
    suspend fun reauthenticated(idToken: String): Boolean {
        val current = _session.value ?: return false
        val refreshed = Session.fromIdToken(idToken)
        if (refreshed.userId != current.userId) return false
        lastExchangeFailure = null
        save(refreshed)
        return true
    }

    suspend fun signOut() {
        runSuspendCatching { google.signOut() }
        store.remove(KEY)
        _session.value = null
    }

    private suspend fun save(session: Session) {
        if (session.expiresAt != _session.value?.expiresAt) lastBackgroundRefresh = null
        store.write(KEY, KapeluszJson.encodeToString(Session.serializer(), session))
        _session.value = session
    }

    companion object {
        const val KEY = "session"
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
        const val EXPIRY_SKEW_MS = 30 * 1000L
        const val REFRESH_RETRY_MS = 15 * 60 * 1000L

        /** A session is renewed once less than this is left of it (sessions last 30 days). */
        const val SESSION_RENEW_WINDOW_MS = 20L * 24 * 3600 * 1000
    }
}
