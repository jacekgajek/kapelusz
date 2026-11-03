package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.server.messaging.FcmSender
import com.jacekgajek.kapelusz.server.messaging.SendResult
import com.jacekgajek.kapelusz.server.messaging.ServiceAccount
import com.jacekgajek.kapelusz.server.messaging.StoredServiceAccount
import com.jacekgajek.kapelusz.server.store.InMemoryBlobStore
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// A local FCM server with its counters, a fresh one per test.
private class FcmFixture {
    val tokenCalls = AtomicInteger()
    var refuseSignIn = false
    private val pool = Executors.newCachedThreadPool()
    private val slow = CountDownLatch(1)
    val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/token") { exchange ->
            tokenCalls.incrementAndGet()
            val body = (
                if (refuseSignIn) """{"error":"invalid_grant"}""" else """{"access_token":"at-1","expires_in":3600}"""
                ).toByteArray()
            exchange.sendResponseHeaders(if (refuseSignIn) 400 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/v1/projects/kapelusz-test/messages:send") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            bodies += body
            check(exchange.requestHeaders.getFirst("authorization") == "Bearer at-1")
            val (status, answer) = when {
                "\"slow\"" in body -> {
                    // Never in time: held until the test is over (the pool's shutdown interrupts it).
                    runCatching { slow.await() }
                    200 to "{}"
                }

                "\"gone\"" in body -> 404 to """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"""

                "\"garbled\"" in body ->
                    400 to
                        """{"error":{"status":"INVALID_ARGUMENT","message":"The registration token """ +
                        """is not a valid FCM registration token"}}"""

                "\"broken\"" in body -> 500 to "{}"

                else -> 200 to """{"name":"projects/kapelusz-test/messages/1"}"""
            }
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"

    val account = run {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder().encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        ServiceAccount("kapelusz-test", "kapelusz-fcm@test.iam.gserviceaccount.com", "key-1", pem, "$base/token")
    }

    fun stop() {
        server.stop(0)
        slow.countDown()
        pool.shutdownNow()
    }
}

private fun withFcm(block: FcmFixture.() -> Unit) {
    val fixture = FcmFixture()
    try {
        fixture.block()
    } finally {
        fixture.stop()
    }
}

/** FCM's HTTP v1 calls against a local server: results, the deadline, the cached access token. */
class FcmSenderTest :
    ExpectSpec({

        expect("each token gets its result within the deadline") {
            withFcm {
                // A roomier deadline than production's, so a busy machine doesn't fail the fast calls.
                val sender = FcmSender({ account }, deadlineMs = 2_000, fcmUrl = base)
                val start = System.nanoTime()
                val results = sender.send(listOf("good", "gone", "garbled", "broken", "slow"), mapOf("band" to "band1"))
                val took = (System.nanoTime() - start) / 1_000_000
                results shouldBe
                    mapOf("good" to SendResult.SENT, "gone" to SendResult.INVALID_TOKEN, "garbled" to SendResult.INVALID_TOKEN, "broken" to SendResult.FAILED, "slow" to SendResult.FAILED)
                withClue("waited ${took}ms: the deadline, not the slow call") { took shouldBeLessThan 4_000 }
                val good = bodies.first { "\"good\"" in it }
                withClue(good) {
                    good shouldContain "\"band\":\"band1\""
                    good shouldContain "\"priority\":\"NORMAL\""
                    good shouldNotContain "notification"
                }
            }
        }

        expect("the access token is kept") {
            withFcm {
                val sender = FcmSender({ account }, fcmUrl = base)
                sender.send(listOf("good"), mapOf("band" to "band1"))
                sender.send(listOf("good"), mapOf("band" to "band1"))
                tokenCalls.get() shouldBe 1
            }
        }

        expect("a refused sign-in is not retried on every message") {
            withFcm {
                refuseSignIn = true
                val sender = FcmSender({ account }, fcmUrl = base)
                sender.send(listOf("good"), mapOf("band" to "band1")) shouldBe mapOf("good" to SendResult.FAILED)
                sender.send(listOf("good"), mapOf("band" to "band1")) shouldBe mapOf("good" to SendResult.FAILED)
                tokenCalls.get() shouldBe 1
            }
        }

        expect("priming needs no network") { FcmSender.prime() }

        expect("the key is read from the bucket once") {
            withFcm {
                val store = InMemoryBlobStore()
                var now = 0L
                val stored = StoredServiceAccount(store) { now }
                withClue("not put there yet") { stored().shouldBeNull() }
                store.put(
                    StorageKeys.FCM_KEY,
                    com.jacekgajek.kapelusz.history.KapeluszJson.encodeToString(ServiceAccount.serializer(), account),
                )
                withClue("looked for again only later") { stored().shouldBeNull() }
                now += StoredServiceAccount.RETRY_MS
                stored()?.projectId shouldBe "kapelusz-test"
            }
        }

        expect("without a key nothing is sent") {
            withFcm {
                val results = FcmSender({ null }, fcmUrl = base).send(listOf("good"), mapOf("band" to "band1"))
                withClue("not set up") { results.shouldBeNull() }
                tokenCalls.get() shouldBe 0
            }
        }
    })
