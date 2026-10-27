package com.jacekgajek.kapelusz.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FCM's HTTP v1 calls (ITERATION_7 §2.5) against a local server: results, the deadline, the cached access token. */
class FcmSenderTest {
    private val tokenCalls = AtomicInteger()
    private var refuseSignIn = false
    private val pool = Executors.newCachedThreadPool()
    private val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/token") { exchange ->
            tokenCalls.incrementAndGet()
            val body = (if (refuseSignIn) """{"error":"invalid_grant"}""" else """{"access_token":"at-1","expires_in":3600}""").toByteArray()
            exchange.sendResponseHeaders(if (refuseSignIn) 400 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/v1/projects/kapelusz-test/messages:send") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            bodies += body
            check(exchange.requestHeaders.getFirst("authorization") == "Bearer at-1")
            val (status, answer) = when {
                "\"slow\"" in body -> { Thread.sleep(5_000); 200 to "{}" }
                "\"gone\"" in body -> 404 to """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"""
                "\"garbled\"" in body -> 400 to """{"error":{"status":"INVALID_ARGUMENT","message":"The registration token is not a valid FCM registration token"}}"""
                "\"broken\"" in body -> 500 to "{}"
                else -> 200 to """{"name":"projects/kapelusz-test/messages/1"}"""
            }
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    private val account = run {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        ServiceAccount("kapelusz-test", "kapelusz-fcm@test.iam.gserviceaccount.com", "key-1", pem, "$base/token")
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    @Test
    fun eachTokenGetsItsResultWithinTheDeadline() {
        // A roomier deadline than production's, so a busy machine doesn't fail the fast calls.
        val sender = FcmSender({ account }, deadlineMs = 2_000, fcmUrl = base)
        val start = System.nanoTime()
        val results = sender.send(listOf("good", "gone", "garbled", "broken", "slow"), mapOf("band" to "band1"))
        val took = (System.nanoTime() - start) / 1_000_000
        assertEquals(
            mapOf("good" to SendResult.SENT, "gone" to SendResult.INVALID_TOKEN, "garbled" to SendResult.INVALID_TOKEN, "broken" to SendResult.FAILED, "slow" to SendResult.FAILED),
            results,
        )
        assertTrue(took < 4_000, "waited ${took}ms: the deadline, not the slow call")
        val good = bodies.first { "\"good\"" in it }
        assertTrue("\"band\":\"band1\"" in good && "\"priority\":\"NORMAL\"" in good && "notification" !in good, good)
    }

    @Test
    fun theAccessTokenIsKept() {
        val sender = FcmSender({ account }, fcmUrl = base)
        sender.send(listOf("good"), mapOf("band" to "band1"))
        sender.send(listOf("good"), mapOf("band" to "band1"))
        assertEquals(1, tokenCalls.get())
    }

    @Test
    fun aRefusedSignInIsNotRetriedOnEveryMessage() {
        refuseSignIn = true
        val sender = FcmSender({ account }, fcmUrl = base)
        assertEquals(mapOf("good" to SendResult.FAILED), sender.send(listOf("good"), mapOf("band" to "band1")))
        assertEquals(mapOf("good" to SendResult.FAILED), sender.send(listOf("good"), mapOf("band" to "band1")))
        assertEquals(1, tokenCalls.get())
    }

    @Test
    fun primingNeedsNoNetwork() = FcmSender.prime()

    @Test
    fun theKeyIsReadFromTheBucketOnce() {
        val store = InMemoryBlobStore()
        var now = 0L
        val stored = StoredServiceAccount(store) { now }
        assertEquals(null, stored(), "not put there yet")
        store.put(StorageKeys.FCM_KEY, com.jacekgajek.kapelusz.model.KapeluszJson.encodeToString(ServiceAccount.serializer(), account))
        assertEquals(null, stored(), "looked for again only later")
        now += StoredServiceAccount.RETRY_MS
        assertEquals("kapelusz-test", stored()?.projectId)
    }

    @Test
    fun withoutAKeyNothingIsSent() {
        val results = FcmSender({ null }, fcmUrl = base).send(listOf("good"), mapOf("band" to "band1"))
        assertEquals(null, results, "not set up")
        assertEquals(0, tokenCalls.get())
    }
}
