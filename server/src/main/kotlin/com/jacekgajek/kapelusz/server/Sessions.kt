package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kapelusz's own session tokens, so the apps need Google only when signing in: a Google ID token lives one hour,
 * and renewing it on Android can show Google's account picker. A token is `as1.<payload>.<mac>` (base64url JSON
 * claims, HMAC-SHA256), valid for [LIFETIME_MS] and renewed by the app while it is used.
 *
 * The signing key lives in the data bucket (created on first use), so it needs no infrastructure: whoever can read
 * the bucket can read every band anyway.
 */
class SessionTokens(private val store: BlobStore, private val clock: () -> Long) {
    @Serializable
    private data class Claims(
        val sub: String,
        val email: String? = null,
        val name: String? = null,
        val exp: Long,
        /** When the chain of renewals began (the Google sign-in); tokens from before this claim count from issue. */
        val since: Long? = null,
    )

    @Serializable
    private data class StoredKey(val key: String)

    @Volatile
    private var key: ByteArray? = null

    /** A new token for [user] and when it expires. */
    fun issue(user: VerifiedUser): Pair<String, Long> {
        val now = clock()
        val since = user.sessionSince ?: now
        // A renewed token ends with the session too: at most [MAX_AGE_MS] after the Google sign-in (ITERATION_3 R6).
        val expiresAt = minOf(now + LIFETIME_MS, since + MAX_AGE_MS)
        val claims = Claims(user.userId, user.email, user.name, expiresAt, since = since)
        val payload = encoder.encodeToString(KapeluszJson.encodeToString(Claims.serializer(), claims).toByteArray())
        return "$PREFIX$payload.${encoder.encodeToString(mac(payload))}" to expiresAt
    }

    /** The user of a valid, unexpired token; null for anything else (including Google tokens). */
    fun verify(token: String): VerifiedUser? {
        if (!token.startsWith(PREFIX)) return null
        val parts = token.removePrefix(PREFIX).split('.')
        if (parts.size != 2) return null
        val signature = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(signature, mac(parts[0]))) return null
        val claims = runCatching { KapeluszJson.decodeFromString(Claims.serializer(), decoder.decode(parts[0]).decodeToString()) }.getOrNull()
            ?: return null
        if (claims.exp <= clock()) return null
        return VerifiedUser(claims.sub, claims.email, claims.name, sessionSince = claims.since ?: (claims.exp - LIFETIME_MS))
    }

    private fun mac(payload: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key(), ALGORITHM)) }.doFinal(payload.toByteArray())

    private fun key(): ByteArray = key ?: loadOrCreateKey().also { key = it }

    private fun loadOrCreateKey(): ByteArray {
        repeat(Api.MAX_ATTEMPTS) {
            store.get(KEY_PATH)?.let { return Base64.getDecoder().decode(KapeluszJson.decodeFromString(StoredKey.serializer(), it.content).key) }
            val fresh = ByteArray(32).also { random.nextBytes(it) }
            try {
                store.put(KEY_PATH, KapeluszJson.encodeToString(StoredKey.serializer(), StoredKey(Base64.getEncoder().encodeToString(fresh))), ifNoneMatch = true)
                return fresh
            } catch (e: PreconditionFailed) {
                // Another instance created it first: use theirs.
            }
        }
        error("Could not load the session key")
    }

    companion object {
        const val PREFIX = "as1."
        const val LIFETIME_MS = 30L * 24 * 3600 * 1000

        /**
         * Renewing a session keeps it alive at most this long after the Google sign-in (ITERATION_3 R6): a stolen
         * token cannot be kept alive for ever; then the app asks Google again.
         */
        const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
        const val KEY_PATH = "server/session-key.json"
        private const val ALGORITHM = "HmacSHA256"
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}
