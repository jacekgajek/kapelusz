package com.jacekgajek.kapelusz.server.messaging

import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The part of a Google service account's JSON key that signing in needs. */
@Serializable
data class ServiceAccount(
    @SerialName("project_id") val projectId: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("private_key_id") val privateKeyId: String,
    @SerialName("private_key") val privateKey: String,
    @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
)

/**
 * Firebase Cloud Messaging's HTTP v1 API (ITERATION_7 §2.5), without the Firebase Admin SDK: one call per token, all
 * at once, within [deadlineMs]. The access token is a self-signed JWT exchanged at Google (within [signInMs] of its
 * own: the first message of an instance opens two connections) and kept until shortly before it expires; a failed
 * sign-in isn't tried again for [SIGN_IN_RETRY_MS].
 */
class FcmSender(
    private val account: () -> ServiceAccount?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deadlineMs: Long = 1_000,
    private val signInMs: Long = 1_500,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build(),
    /** Tests point it at a local server. */
    private val fcmUrl: String = "https://fcm.googleapis.com",
) : MessageSender {
    private var accessToken: Pair<String, Long>? = null
    private var signInFailedAt: Long? = null

    override fun ready(): Boolean = account() != null

    override fun send(tokens: List<String>, data: Map<String, String>): Map<String, SendResult>? {
        val failed = tokens.associateWith { SendResult.FAILED }
        val account = runCatching { account() }.getOrNull() ?: return null
        val bearer = runCatching { bearer(account) }.getOrElse {
            System.err.println("FCM sign-in failed: $it")
            return failed
        } ?: return failed
        val deadline = clock() + deadlineMs
        val url = URI("$fcmUrl/v1/projects/${account.projectId}/messages:send")
        val calls = tokens.associateWith { token ->
            val request = java.net.http.HttpRequest.newBuilder(url)
                .timeout(remaining(deadline))
                .header("authorization", "Bearer $bearer")
                .header("content-type", "application/json; charset=utf-8")
                .POST(BodyPublishers.ofString(message(token, data).toString()))
                .build()
            http.sendAsync(request, BodyHandlers.ofString())
        }
        runCatching { CompletableFuture.allOf(*calls.values.toTypedArray()).get(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS) }
        return calls.mapValues { (_, call) ->
            // Not answered in time, or failed (a timeout, no connection): it doesn't matter which.
            if (!call.isDone || call.isCompletedExceptionally) return@mapValues SendResult.FAILED.also { call.cancel(true) }
            val response = call.join()
            when {
                response.statusCode() == 200 -> SendResult.SENT

                // The access token was refused (revoked key, clock skew): signed in again on the next message.
                response.statusCode() == 401 -> SendResult.FAILED.also { synchronized(this) { accessToken = null } }

                // UNREGISTERED (404), or a token FCM can't read at all (400 INVALID_ARGUMENT about the token).
                response.statusCode() == 404 || (response.statusCode() == 400 && "registration token" in response.body()) -> SendResult.INVALID_TOKEN

                else -> SendResult.FAILED.also { System.err.println("FCM ${response.statusCode()}: ${response.body().take(300)}") }
            }
        }
    }

    private fun remaining(deadline: Long): Duration = Duration.ofMillis((deadline - clock()).coerceAtLeast(1))

    /** A data message: the app syncs, nothing is shown. Normal priority (ITERATION_7 §2.3); worthless after 10 minutes. */
    private fun message(token: String, data: Map<String, String>): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            putJsonObject("data") { data.forEach { (key, value) -> put(key, value) } }
            putJsonObject("android") {
                put("priority", "NORMAL")
                put("ttl", "600s")
            }
        }
    }

    /** The access token; null while a failed sign-in waits to be tried again. */
    @Synchronized
    private fun bearer(account: ServiceAccount): String? {
        accessToken?.let { (token, expiresAt) -> if (clock() < expiresAt - REFRESH_MARGIN_MS) return token }
        signInFailedAt?.let { if (clock() - it < SIGN_IN_RETRY_MS) return null }
        return runCatching { signIn(account) }.onFailure { signInFailedAt = clock() }.getOrThrow().also { signInFailedAt = null }
    }

    private fun signIn(account: ServiceAccount): String {
        val deadline = clock() + signInMs
        val now = clock()
        val claims = JWTClaimsSet.Builder()
            .issuer(account.clientEmail)
            .audience(account.tokenUri)
            .claim("scope", SCOPE)
            .issueTime(Date(now))
            .expirationTime(Date(now + 3600_000))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(account.privateKeyId).build(), claims)
        jwt.sign(RSASSASigner(privateKey(account.privateKey)))
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) + "&assertion=" + jwt.serialize()
        val request = java.net.http.HttpRequest.newBuilder(URI(account.tokenUri))
            .timeout(remaining(deadline))
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, BodyHandlers.ofString())
        check(response.statusCode() == 200) { "token endpoint ${response.statusCode()}: ${response.body().take(200)}" }
        val body = decode(JsonObject.serializer(), response.body())
        val token = body["access_token"]!!.jsonPrimitive.content
        val expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        accessToken = token to now + expiresIn * 1000
        return token
    }

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        private const val REFRESH_MARGIN_MS = 5 * 60_000L
        const val SIGN_IN_RETRY_MS = 60_000L

        /**
         * SnapStart priming (Handler): loads and warms the signing code with a throwaway key, no network, so an
         * instance's first message doesn't spend its time budget on that.
         */
        fun prime() {
            val keys = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val claims = JWTClaimsSet.Builder().issuer("prime").audience("prime").issueTime(Date()).build()
            SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("prime").build(), claims).apply { sign(RSASSASigner(keys.private)) }.serialize()
            privateKey("-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----")
        }

        fun privateKey(pem: String): RSAPrivateKey {
            val der = Base64.getMimeDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), ""))
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
        }
    }
}

/**
 * The FCM key (ITERATION_7 §2.5) in the data bucket, next to the session signing key ([StorageKeys.FCM_KEY]), put
 * there by hand: no infrastructure, and whoever can read the bucket could read the bands anyway. Read on first use
 * (not into the SnapStart snapshot) and kept; missing or unreadable means no messages, looked for again after
 * [RETRY_MS].
 */
class StoredServiceAccount(private val store: BlobStore, private val clock: () -> Long = System::currentTimeMillis) : () -> ServiceAccount? {
    private var account: ServiceAccount? = null
    private var missingAt: Long? = null

    @Synchronized
    override fun invoke(): ServiceAccount? {
        account?.let { return it }
        missingAt?.let { if (clock() - it < RETRY_MS) return null }
        return runCatching { store.get(StorageKeys.FCM_KEY)?.let { decode(ServiceAccount.serializer(), it.content) } }
            .onFailure { System.err.println("FCM key in ${StorageKeys.FCM_KEY}: $it") }.getOrNull()
            .also {
                account = it
                missingAt = if (it == null) clock() else null
            }
    }

    companion object {
        const val RETRY_MS = 5 * 60_000L
    }
}
