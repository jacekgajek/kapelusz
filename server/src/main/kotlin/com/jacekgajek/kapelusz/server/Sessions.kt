package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kapelusz's own session tokens, so the apps need Google only when signing in: a Google ID token lives one hour,
 * and renewing it on Android can show Google's account picker. A token is `as1.<payload>.<mac>` (base64url JSON
 * claims, HMAC-SHA256), valid for [LIFETIME_MS] and renewed by the app while it is used.
 *
 * The signing key lives in the data bucket ([StorageKeys.SESSION_KEY], created on first use), so it needs no infrastructure: whoever can read
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

    // A precondition failure: another instance created it first, so the next attempt uses theirs.
    private fun loadOrCreateKey(): ByteArray = retryOnConflict(exhausted = { error("Could not load the session key") }) {
        store.get(StorageKeys.SESSION_KEY)?.let { return@retryOnConflict Base64.getDecoder().decode(KapeluszJson.decodeFromString(StoredKey.serializer(), it.content).key) }
        val fresh = ByteArray(32).also { random.nextBytes(it) }
        store.put(StorageKeys.SESSION_KEY, KapeluszJson.encodeToString(StoredKey.serializer(), StoredKey(Base64.getEncoder().encodeToString(fresh))), ifNoneMatch = true)
        fresh
    }

    companion object {
        const val PREFIX = "as1."
        const val LIFETIME_MS = 30L * 24 * 3600 * 1000

        /**
         * Renewing a session keeps it alive at most this long after the Google sign-in (ITERATION_3 R6): a stolen
         * token cannot be kept alive for ever; then the app asks Google again.
         */
        const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
        private const val ALGORITHM = "HmacSHA256"
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}

/**
 * "Signed out everywhere" (ITERATION_3 R6): session tokens whose chain began ([VerifiedUser.sessionSince]) before the
 * user's [UserRecord.sessionsValidFrom] are refused, on every request and on renewal. A Google sign-in afterwards
 * starts a new chain (its `since` is the time of that sign-in), so it works again.
 *
 * Google ID tokens issued before it ([VerifiedUser.issuedAt]) are refused too: the other devices keep theirs for up to
 * an hour and would otherwise exchange it for a new session.
 *
 * The time is kept in memory for at most [CACHE_MS] per user, so a request needs no extra read: other Lambda instances
 * may accept an old token for up to that long after a revoke; the instance that made it refuses it at once.
 */
class SessionRevocations(private val store: BlobStore, private val clock: () -> Long) {
    private class Cached(val validFrom: Long, val readAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Whether a session of [userId] begun at [since] is still valid. */
    fun isValid(userId: String, since: Long): Boolean = since >= validFrom(userId)

    /** Every session of [userId] begun before now stops working. */
    fun revoke(userId: String) {
        val key = StorageKeys.user(userId)
        val now = clock()
        val validFrom = retryOnConflict(exhausted = { throw busy("Could not update the user record") }) {
            val blob = store.get(key)
            val record = blob?.let { decode(UserRecord.serializer(), it.content) } ?: UserRecord(userId)
            // Never earlier than a revoke already stored (e.g. another device's, a moment later by its clock).
            if (record.sessionsValidFrom >= now) return@retryOnConflict record.sessionsValidFrom
            store.put(key, encode(UserRecord.serializer(), record.copy(sessionsValidFrom = now)), ifMatch = blob?.etag, ifNoneMatch = blob == null)
            now
        }
        cache[userId] = Cached(validFrom, now)
    }

    private fun validFrom(userId: String): Long {
        val now = clock()
        cache[userId]?.takeIf { now - it.readAt < CACHE_MS }?.let { return it.validFrom }
        // An unreadable record must not refuse every request of the user: it counts as never revoked. A storage
        // failure is still an error.
        val validFrom = store.get(StorageKeys.user(userId))
            ?.let { blob -> runCatching { decode(UserRecord.serializer(), blob.content).sessionsValidFrom }.getOrDefault(0) } ?: 0
        cache[userId] = Cached(validFrom, now)
        return validFrom
    }

    companion object {
        /** How long a Lambda instance trusts what it read; a revoke reaches the others at most this late. */
        const val CACHE_MS = 60_000L
    }
}
