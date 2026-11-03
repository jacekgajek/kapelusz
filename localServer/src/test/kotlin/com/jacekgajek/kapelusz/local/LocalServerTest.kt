package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.server.store.HistoryRecord
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.util.Base64

/** A local server on a temporary data directory, a fresh one per test. */
private class LocalFixture {
    val dataDir = Files.createTempDirectory("kapelusz-local")
    val server = LocalServer(dataDir, 0).also { it.start() }
    val base = "http://localhost:${server.port}"
    val http = HttpClient.newHttpClient()

    fun stop() {
        server.stop()
        dataDir.toFile().deleteRecursively()
    }

    fun signIn(name: String): String {
        val response = send("POST", "/session", devToken(name))
        withClue(response.body()) { response.statusCode() shouldBe 200 }
        return KapeluszJson.decodeFromString(SessionResponse.serializer(), response.body()).token
    }

    fun send(method: String, path: String, bearer: String, body: String? = null, headers: Map<String, String> = emptyMap()) = http.send(
        HttpRequest.newBuilder(URI("$base$path${if ('?' in path) "&" else "?"}v=0"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("authorization", "Bearer $bearer")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build(),
        BodyHandlers.ofString(),
    )
}

private fun withLocal(block: LocalFixture.() -> Unit) {
    val fixture = LocalFixture()
    try {
        fixture.block()
    } finally {
        fixture.stop()
    }
}

/** As the app's development sign-in makes it. */
private fun devToken(name: String, expires: Long = System.currentTimeMillis() / 1000 + 3600): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun part(json: String) = encoder.encodeToString(json.toByteArray())
    return part("""{"alg":"none","typ":"JWT"}""") + "." +
        part("""{"sub":"dev-$name","name":"$name","email":"$name@dev.local","exp":$expires}""") + "."
}

class LocalServerTest :
    ExpectSpec({

        expect("a dev sign-in gets a session that opens the API") {
            withLocal {
                val session = signIn("alice")
                val created = send("POST", "/bands", session, KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("b1", "Band", "PLN", "m1", "Alice")))
                withClue(created.body()) { created.statusCode() shouldBe 200 }
                val state = send("GET", "/bands/b1/state", session)
                state.statusCode() shouldBe 200
                val etag = state.headers().firstValue("etag").orElse(null).shouldNotBeNull()
                withClue("unchanged since") {
                    send("GET", "/bands/b1/state", session, headers = mapOf("if-none-match" to etag)).statusCode() shouldBe 304
                }
                withClue("the web app may call it") { state.headers().firstValue("access-control-allow-origin").orElse(null) shouldBe "*" }
                withClue("not Bob's band") { send("GET", "/bands/b1/state", signIn("bob")).statusCode() shouldBe 404 }
            }
        }

        expect("the data outlives the server") {
            withLocal {
                val session = signIn("alice")
                send("POST", "/bands", session, KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("b1", "Band", "PLN", "m1", "Alice")))
                server.stop()
                val again = LocalServer(dataDir, 0).also { it.start() }
                try {
                    val response = http.send(
                        HttpRequest.newBuilder(URI("http://localhost:${again.port}/bands/b1/state?v=0")).header("authorization", "Bearer $session").build(),
                        BodyHandlers.ofString(),
                    )
                    withClue("the session key and the band are in the data directory") { response.statusCode() shouldBe 200 }
                } finally {
                    again.stop()
                }
            }
        }

        expect("a forged or foreign token is refused") {
            withLocal {
                send("POST", "/session", "not-a-token").statusCode() shouldBe 401
                withClue("expired") { send("POST", "/session", devToken("alice", expires = 1)).statusCode() shouldBe 401 }
                withClue("not a dev token any more") {
                    send("POST", "/session", devToken("alice").dropLast(1) + "x.").statusCode() shouldBe 401
                }
            }
        }

        expect("places are made up offline") {
            withLocal {
                val session = signIn("alice")
                send("POST", "/bands", session, KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("b1", "Band", "PLN", "m1", "Alice")))
                val suggestions = send("GET", "/bands/b1/places?q=${URLEncoder.encode("Klub Żaczek", Charsets.UTF_8)}", session)
                withClue(suggestions.body()) { suggestions.statusCode() shouldBe 200 }
                val place = KapeluszJson.decodeFromString(PlacesResponse.serializer(), suggestions.body()).places.single()
                place.name shouldBe "Klub Żaczek"
                val details = send("GET", "/bands/b1/places/${place.placeId}", session)
                withClue(details.body()) { details.statusCode() shouldBe 200 }
                KapeluszJson.decodeFromString(PlaceText.serializer(), details.body()).text shouldStartWith "Klub Żaczek"
                send("GET", "/bands/b1/locations", session).statusCode() shouldBe 200
            }
        }

        expect("a presigned upload creates the file once and its link serves it") {
            withLocal {
                val store = FileBlobStore(dataDir.resolve("bucket")) { base }
                val put = store.presignPut("bands/b1/attachments/a1.pdf", "application/pdf", 3)
                val upload = { body: ByteArray -> http.send(HttpRequest.newBuilder(URI(put.url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString()).statusCode() }
                withClue("not the signed size") { upload(byteArrayOf(1, 2)) shouldBe 403 }
                upload(byteArrayOf(1, 2, 3)) shouldBe 200
                withClue("never replaced") { upload(byteArrayOf(4, 5, 6)) shouldBe 412 }
                val download = http.send(HttpRequest.newBuilder(URI(store.presignGet("bands/b1/attachments/a1.pdf", "application/pdf", "Faktura 1/2.pdf"))).build(), BodyHandlers.ofByteArray())
                download.statusCode() shouldBe 200
                download.body().toList() shouldBe listOf<Byte>(1, 2, 3)
                download.headers().firstValue("content-type").orElse(null) shouldBe "application/pdf"
            }
        }

        expect("the file store writes conditionally") {
            withLocal {
                val store = FileBlobStore(dataDir.resolve("bucket")) { base }
                val first = store.put("users/u.json", "{}", ifNoneMatch = true)
                shouldThrow<PreconditionFailed> { store.put("users/u.json", "{}", ifNoneMatch = true) }
                val second = store.put("users/u.json", """{"a":1}""", ifMatch = first)
                shouldThrow<PreconditionFailed> { store.put("users/u.json", "{}", ifMatch = first) }
                store.get("users/u.json")?.etag shouldBe second
                shouldThrow<PreconditionFailed> { store.put("users/none.json", "{}", ifMatch = first) }
                store.list("users/") shouldBe listOf("users/u.json")
                shouldThrow<IllegalArgumentException> { store.get("../outside.json") }
                store.delete("users/u.json")
                store.get("users/u.json").shouldBeNull()
            }
        }

        expect("history comes newest first within the range") {
            withLocal {
                val history = FileHistoryStore(dataDir.resolve("history"))
                history.put("b1", listOf("2026-09/1-x.json#000", "2026-10/2-y.json#000", "2026-10/2-y.json#001").map { HistoryRecord(it, "{\"k\":\"$it\"}") })
                history.newestFirst("b1", "2026-10", "2026-10~").map { it.key }.toList() shouldBe
                    listOf("2026-10/2-y.json#001", "2026-10/2-y.json#000")
                history.newestFirst("b1", "", "~").last().content shouldBe "{\"k\":\"2026-09/1-x.json#000\"}"
                history.deleteBand("b1")
                history.newestFirst("b1", "", "~").toList() shouldBe emptyList()
            }
        }
    })
