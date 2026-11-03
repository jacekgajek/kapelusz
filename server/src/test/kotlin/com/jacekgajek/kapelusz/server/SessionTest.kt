package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.server.auth.SessionRevocations
import com.jacekgajek.kapelusz.server.auth.SessionTokens
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.store.InMemoryBlobStore
import com.jacekgajek.kapelusz.server.store.InMemoryHistoryStore
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.UserRecord
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

// "Google" accepts any token that looks like a Google one: "google-<sub>", or "google-<sub>@<issued at>".
private class SessionApi :
    ApiHarness(verifier = { token ->
        token.removePrefix("google-").takeIf { token.startsWith("google-") }?.split('@')?.let { parts ->
            VerifiedUser(parts[0], "${parts[0]}@example.com", parts[0], issuedAt = parts.getOrNull(1)?.toLong())
        }
    }) {
    fun session(bearer: String): SessionResponse {
        val response = call("POST", "/session", bearer)
        withClue(response.body) { response.status shouldBe 200 }
        return KapeluszJson.decodeFromString(SessionResponse.serializer(), response.body!!)
    }
}

class SessionTest :
    ExpectSpec({

        expect("a Google token buys a session that works and renews") {
            with(SessionApi()) {
                val first = session("google-alice")
                first.token shouldStartWith SessionTokens.PREFIX
                first.expiresAt shouldBe now + SessionTokens.LIFETIME_MS
                val me = call("GET", "/me", first.token)
                KapeluszJson.decodeFromString(MeResponse.serializer(), me.body!!).userId shouldBe "alice"

                now += 1000
                val renewed = session(first.token)
                renewed.token shouldNotBe first.token
                renewed.expiresAt shouldBe now + SessionTokens.LIFETIME_MS
            }
        }

        expect("expired or tampered sessions are refused") {
            with(SessionApi()) {
                val token = session("google-alice").token
                val (payload, mac) = token.removePrefix(SessionTokens.PREFIX).split('.')
                val forged = SessionTokens.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Base64.getUrlDecoder().decode(payload).decodeToString().replace("alice", "mallo").toByteArray()) + ".$mac"
                call("GET", "/me", forged).status shouldBe 401
                call("GET", "/me", "${SessionTokens.PREFIX}garbage").status shouldBe 401

                now += SessionTokens.LIFETIME_MS
                call("GET", "/me", token).status shouldBe 401
            }
        }

        expect("the key is created once and shared") {
            with(SessionApi()) {
                val token = session("google-alice").token
                store.get(StorageKeys.SESSION_KEY).shouldNotBeNull()
                // Another Lambda instance (a fresh SessionTokens) reads the same key and accepts the token.
                SessionTokens(store) { now }.verify(token)?.userId shouldBe "alice"
                withClue("a different key does not") { SessionTokens(InMemoryBlobStore()) { now }.verify(token).shouldBeNull() }
            }
        }

        expect("renewals keep a session alive at most 90 days after the Google sign-in") {
            with(SessionApi()) {
                var token = session("google-alice").token
                // Renewed every 25 days, it lives on until 90 days after the sign-in ...
                repeat(3) {
                    now += 25L * 24 * 3600 * 1000
                    token = session(token).token
                }
                // ... the last token ends with the session, not 30 days after its renewal ...
                now += 14L * 24 * 3600 * 1000
                withClue("day 89") { call("GET", "/me", token).status shouldBe 200 }
                // ... then the token and its renewal are refused (the app asks Google again).
                now += 2L * 24 * 3600 * 1000
                withClue("day 91") { call("GET", "/me", token).status shouldBe 401 }
                call("POST", "/session", token).status shouldBe 401
                withClue("a fresh Google sign-in starts a new chain") { call("POST", "/session", "google-alice").status shouldBe 200 }
            }
        }

        // "sign out on all devices" ends every session begun before it, for this user only.
        expect("revoking ends every session of the user but not new sign-ins") {
            with(SessionApi()) {
                val phone = session("google-alice").token
                now += 1000
                val laptop = session("google-alice").token
                val bob = session("google-bob").token
                now += 1000

                call("POST", "/sessions/revoke", phone).status shouldBe 204
                call("GET", "/me", phone).status shouldBe 401
                call("GET", "/me", laptop).status shouldBe 401
                withClue("nor can it be renewed") { call("POST", "/session", laptop).status shouldBe 401 }
                withClue("another user is not affected") { call("GET", "/me", bob).status shouldBe 200 }

                // A Google sign-in in the same millisecond as the revoke starts a valid session.
                val fresh = session("google-alice").token
                call("GET", "/me", fresh).status shouldBe 200
                now += 1000
                withClue("and it renews") { call("GET", "/me", session(fresh).token).status shouldBe 200 }
            }
        }

        // A Google token issued before the revoke would let the other devices back in silently: refused, distinctly.
        expect("a Google token issued before the revoke is refused") {
            with(SessionApi()) {
                val issuedBefore = "google-alice@${now - 1000}"
                val phone = session(issuedBefore).token
                now += 1000
                call("POST", "/sessions/revoke", phone).status shouldBe 204

                val refusals = listOf(call("POST", "/session", issuedBefore), call("GET", "/me", issuedBefore), call("GET", "/me", phone))
                for (refused in refusals) {
                    refused.status shouldBe 401
                    KapeluszJson.decodeFromString(ErrorResponse.serializer(), refused.body!!).error shouldBe ApiErrors.SESSIONS_REVOKED
                }
                // Anything else that is refused stays a plain "unauthorized".
                KapeluszJson.decodeFromString(ErrorResponse.serializer(), call("GET", "/me", "nonsense").body!!).error shouldBe
                    ApiErrors.UNAUTHORIZED

                now += 1000
                val issuedAfter = "google-alice@$now"
                call("GET", "/me", issuedAfter).status shouldBe 200
                call("GET", "/me", session(issuedAfter).token).status shouldBe 200
            }
        }

        // An unreadable user record must not lock its user out: it counts as never revoked.
        expect("an unreadable user record does not refuse the user") {
            with(SessionApi()) {
                val token = session("google-alice").token
                store.put(StorageKeys.user("alice"), "{not json")
                // A fresh instance, which has not read the record yet. (A band of someone else: the check reads no user record.)
                val other = Api(store, InMemoryHistoryStore(), { null }, clock = { now })
                other.handle(HttpRequest("GET", "/bands/nope/state", mapOf("authorization" to "Bearer $token"))).status shouldBe 404
            }
        }

        expect("another instance learns of the revoke within a minute") {
            with(SessionApi()) {
                val token = session("google-alice").token
                val other = Api(store, InMemoryHistoryStore(), { null }, clock = { now })
                other.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer $token"))).status shouldBe 200
                now += 1000
                call("POST", "/sessions/revoke", token).status shouldBe 204
                KapeluszJson.decodeFromString(UserRecord.serializer(), store.get(StorageKeys.user("alice"))!!.content)
                    .sessionsValidFrom shouldBe now
                // The other instance read the time before the revoke and trusts it for up to a minute ...
                now += SessionRevocations.CACHE_MS - 1001
                other.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer $token"))).status shouldBe 200
                // ... then reads it again.
                now += 1
                other.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer $token"))).status shouldBe 401
            }
        }
    })
