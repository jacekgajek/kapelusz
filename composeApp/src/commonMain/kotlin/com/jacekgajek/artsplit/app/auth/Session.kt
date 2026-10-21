package com.jacekgajek.artsplit.app.auth

import com.jacekgajek.artsplit.app.data.KeyValueStore
import com.jacekgajek.artsplit.model.ArtSplitJson
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
) {
    companion object {
        /** Reads the claims of a Google ID token. The server verifies the signature; the app only needs the content. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromIdToken(idToken: String): Session {
            val payload = idToken.split('.').getOrNull(1) ?: error("Not a JWT")
            val json = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
            val claims = ArtSplitJson.parseToJsonElement(json).jsonObject
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

/** Keeps the session, persists it, and refreshes the ID token shortly before it expires. */
class SessionRepository(
    private val store: KeyValueStore,
    private val google: GoogleAuth,
    private val clock: () -> Long,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val refreshLock = Mutex()

    /** When a renewal was last attempted without the server having refused the token. */
    private var lastBackgroundRefresh: Long? = null

    suspend fun load() {
        _session.value = store.read(KEY)?.let { raw -> runCatching { ArtSplitJson.decodeFromString(Session.serializer(), raw) }.getOrNull() }
    }

    suspend fun signedIn(idToken: String): Session {
        val session = Session.fromIdToken(idToken)
        save(session)
        return session
    }

    /**
     * A token valid for at least a few more minutes, refreshing it silently if needed. Null when the user has to sign
     * in again (the session itself is kept, so the app keeps working offline).
     */
    suspend fun validToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val current = _session.value ?: return null
        if (!forceRefresh && current.expiresAt - clock() > REFRESH_MARGIN_MS) return current.idToken
        // Without a fresh token, the current one is still fine until it actually expires (unless the server refused it).
        val stillValid = current.idToken.takeIf { !forceRefresh && current.expiresAt - clock() > EXPIRY_SKEW_MS }
        // Every API request asks for a token. On Android a renewal that cannot auto-select shows Google's account
        // picker, so renewing on each request near expiry popped it up every few seconds. Background renewals are
        // tried once per REFRESH_RETRY_MS; only a token the server just refused is renewed right away.
        val now = clock()
        if (!forceRefresh && lastBackgroundRefresh?.let { now - it < REFRESH_RETRY_MS } == true) return stillValid
        if (!forceRefresh) lastBackgroundRefresh = now
        val fresh = runCatching { google.refresh() }.getOrNull() ?: return stillValid
        val refreshed = runCatching { Session.fromIdToken(fresh) }.getOrNull() ?: return stillValid
        if (refreshed.userId != current.userId) return stillValid
        // Google may hand back its cached token: that is no renewal (and saving it would ask again right away).
        if (refreshed.expiresAt <= current.expiresAt) return stillValid
        save(refreshed)
        refreshed.idToken
    }

    /**
     * After the user signed in again interactively (e.g. from the "Sign in again" dialog). Must be the same account,
     * because this device's data belongs to it. Returns false for a different account.
     */
    suspend fun reauthenticated(idToken: String): Boolean {
        val current = _session.value ?: return false
        val refreshed = Session.fromIdToken(idToken)
        if (refreshed.userId != current.userId) return false
        save(refreshed)
        return true
    }

    suspend fun signOut() {
        runCatching { google.signOut() }
        store.remove(KEY)
        _session.value = null
    }

    private suspend fun save(session: Session) {
        if (session.expiresAt != _session.value?.expiresAt) lastBackgroundRefresh = null
        store.write(KEY, ArtSplitJson.encodeToString(Session.serializer(), session))
        _session.value = session
    }

    companion object {
        const val KEY = "session"
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
        const val EXPIRY_SKEW_MS = 30 * 1000L
        const val REFRESH_RETRY_MS = 15 * 60 * 1000L
    }
}
