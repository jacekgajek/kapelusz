package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.api.SessionResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.server.store.HistoryRecord
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalServerTest {
    private val dataDir = Files.createTempDirectory("kapelusz-local")
    private val server = LocalServer(dataDir, 0).also { it.start() }
    private val base = "http://localhost:${server.port}"
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        server.stop()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun aDevSignInGetsASessionThatOpensTheApi() {
        val session = signIn("alice")
        val created = send("POST", "/bands", session, KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("b1", "Band", "PLN", "m1", "Alice")))
        assertEquals(200, created.statusCode(), created.body())
        val state = send("GET", "/bands/b1/state", session)
        assertEquals(200, state.statusCode())
        val etag = assertNotNull(state.headers().firstValue("etag").orElse(null))
        assertEquals(304, send("GET", "/bands/b1/state", session, headers = mapOf("if-none-match" to etag)).statusCode(), "unchanged since")
        assertEquals("*", state.headers().firstValue("access-control-allow-origin").orElse(null), "the web app may call it")
        assertEquals(404, send("GET", "/bands/b1/state", signIn("bob")).statusCode(), "not Bob's band")
    }

    @Test
    fun theDataOutlivesTheServer() {
        val session = signIn("alice")
        send("POST", "/bands", session, KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("b1", "Band", "PLN", "m1", "Alice")))
        server.stop()
        val again = LocalServer(dataDir, 0).also { it.start() }
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI("http://localhost:${again.port}/bands/b1/state?v=0")).header("authorization", "Bearer $session").build(),
                BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode(), "the session key and the band are in the data directory")
        } finally {
            again.stop()
        }
    }

    @Test
    fun aForgedOrForeignTokenIsRefused() {
        assertEquals(401, send("POST", "/session", "not-a-token").statusCode())
        assertEquals(401, send("POST", "/session", devToken("alice", expires = 1)).statusCode(), "expired")
        assertEquals(401, send("POST", "/session", devToken("alice").dropLast(1) + "x.").statusCode(), "not a dev token any more")
    }

    @Test
    fun placesAreMadeUpOffline() {
        val session = signIn("alice")
        send("POST", "/bands", session, KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("b1", "Band", "PLN", "m1", "Alice")))
        val suggestions = send("GET", "/bands/b1/places?q=${URLEncoder.encode("Klub Żaczek", Charsets.UTF_8)}", session)
        assertEquals(200, suggestions.statusCode(), suggestions.body())
        val place = KapeluszJson.decodeFromString(PlacesResponse.serializer(), suggestions.body()).places.single()
        assertEquals("Klub Żaczek", place.name)
        val details = send("GET", "/bands/b1/places/${place.placeId}", session)
        assertEquals(200, details.statusCode(), details.body())
        assertTrue(KapeluszJson.decodeFromString(PlaceText.serializer(), details.body()).text.startsWith("Klub Żaczek"))
        assertEquals(200, send("GET", "/bands/b1/locations", session).statusCode())
    }

    @Test
    fun aPresignedUploadCreatesTheFileOnceAndItsLinkServesIt() {
        val store = FileBlobStore(dataDir.resolve("bucket")) { base }
        val put = store.presignPut("bands/b1/attachments/a1.pdf", "application/pdf", 3)
        val upload = { body: ByteArray -> http.send(HttpRequest.newBuilder(URI(put.url)).PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString()).statusCode() }
        assertEquals(403, upload(byteArrayOf(1, 2)), "not the signed size")
        assertEquals(200, upload(byteArrayOf(1, 2, 3)))
        assertEquals(412, upload(byteArrayOf(4, 5, 6)), "never replaced")
        val download = http.send(HttpRequest.newBuilder(URI(store.presignGet("bands/b1/attachments/a1.pdf", "application/pdf", "Faktura 1/2.pdf"))).build(), BodyHandlers.ofByteArray())
        assertEquals(200, download.statusCode())
        assertEquals(listOf<Byte>(1, 2, 3), download.body().toList())
        assertEquals("application/pdf", download.headers().firstValue("content-type").orElse(null))
    }

    @Test
    fun theFileStoreWritesConditionally() {
        val store = FileBlobStore(dataDir.resolve("bucket")) { base }
        val first = store.put("users/u.json", "{}", ifNoneMatch = true)
        assertFailsWith<PreconditionFailed> { store.put("users/u.json", "{}", ifNoneMatch = true) }
        val second = store.put("users/u.json", """{"a":1}""", ifMatch = first)
        assertFailsWith<PreconditionFailed> { store.put("users/u.json", "{}", ifMatch = first) }
        assertEquals(second, store.get("users/u.json")?.etag)
        assertFailsWith<PreconditionFailed> { store.put("users/none.json", "{}", ifMatch = first) }
        assertEquals(listOf("users/u.json"), store.list("users/"))
        assertFailsWith<IllegalArgumentException> { store.get("../outside.json") }
        store.delete("users/u.json")
        assertNull(store.get("users/u.json"))
    }

    @Test
    fun historyComesNewestFirstWithinTheRange() {
        val history = FileHistoryStore(dataDir.resolve("history"))
        history.put("b1", listOf("2026-09/1-x.json#000", "2026-10/2-y.json#000", "2026-10/2-y.json#001").map { HistoryRecord(it, "{\"k\":\"$it\"}") })
        assertEquals(listOf("2026-10/2-y.json#001", "2026-10/2-y.json#000"), history.newestFirst("b1", "2026-10", "2026-10~").map { it.key }.toList())
        assertEquals("{\"k\":\"2026-09/1-x.json#000\"}", history.newestFirst("b1", "", "~").last().content)
        history.deleteBand("b1")
        assertEquals(emptyList(), history.newestFirst("b1", "", "~").toList())
    }

    private fun signIn(name: String): String {
        val response = send("POST", "/session", devToken(name))
        assertEquals(200, response.statusCode(), response.body())
        return KapeluszJson.decodeFromString(SessionResponse.serializer(), response.body()).token
    }

    private fun send(method: String, path: String, bearer: String, body: String? = null, headers: Map<String, String> = emptyMap()) = http.send(
        HttpRequest.newBuilder(URI("$base$path${if ('?' in path) "&" else "?"}v=0"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("authorization", "Bearer $bearer")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build(),
        BodyHandlers.ofString(),
    )

    /** As the app's development sign-in makes it. */
    private fun devToken(name: String, expires: Long = System.currentTimeMillis() / 1000 + 3600): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun part(json: String) = encoder.encodeToString(json.toByteArray())
        return part("""{"alg":"none","typ":"JWT"}""") + "." + part("""{"sub":"dev-$name","name":"$name","email":"$name@dev.local","exp":$expires}""") + "."
    }
}
