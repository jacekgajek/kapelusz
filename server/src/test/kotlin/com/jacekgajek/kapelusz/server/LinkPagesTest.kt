package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class LinkPagesTest {
    private val store = InMemoryBlobStore()
    private var now = 1_790_000_000_000
    private val page = """
        <html>
        <head>
            <meta property="og:site_name" content="Kapelusz">
            <meta property="og:title" content="Kapelusz · Pass the Hat">
            <meta property="og:image" content="https://example.com/icon.png">
        </head>
        <body></body>
        </html>
    """.trimIndent()
    private var index: String? = page
    private val api = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, clock = { now }, webIndex = { index })

    private fun call(method: String, path: String, user: String? = null, body: String? = null) = api.handle(HttpRequest(method, path, listOfNotNull(user?.let { "authorization" to "Bearer $it" }).toMap(), body))

    private var seq = 0L
    private fun push(vararg ops: Operation) = call(
        "POST",
        "/bands/band1/ops",
        "alice",
        KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops.map { OpEnvelope("op${++seq}", "phone", seq, seq, it) })),
    ).also { assertEquals(200, it.status, it.body) }

    private fun setup() {
        call("POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        push(CreateEvent("ev1", "Wesele \"Kowalskich\" <3", LocalDate(2026, 11, 14)))
    }

    private fun HttpResponse.title() = Regex("""og:title" content="([^"]*)"""").find(body.orEmpty())?.groupValues?.get(1)
    private fun HttpResponse.description() = Regex("""og:description" content="([^"]*)"""").find(body.orEmpty())?.groupValues?.get(1)

    @Test
    fun anEventLinkShowsItsNameAndDateOnly() {
        setup()
        val response = call("GET", "/e/ev1")
        assertEquals(200, response.status)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertEquals("Wesele &quot;Kowalskich&quot; &lt;3", response.title(), "escaped")
        assertEquals("14.11.2026", response.description())
        assertTrue("og:image" in response.body.orEmpty() && "<body></body>" in response.body.orEmpty(), "the rest of the page as it is")
        assertNull(call("HEAD", "/e/ev1").body)
    }

    @Test
    fun anythingElseKeepsTheGenericCard() {
        setup()
        assertEquals("Kapelusz · Pass the Hat", call("GET", "/e/nope").title())
        push(DeleteEvent("ev1"))
        assertEquals("Kapelusz · Pass the Hat", call("GET", "/e/ev1").title())
        assertNull(call("GET", "/e/nope").description())
        assertEquals(404, call("GET", "/e/a/b").status)
        assertEquals(404, call("POST", "/e/ev1").status)
    }

    @Test
    fun anotherBandCannotTakeOverAnEventsCard() {
        setup()
        call("POST", "/bands", "mallory", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band2", "Spoof", "PLN", "m-mallory", "Mallory")))
        val spoof = call(
            "POST",
            "/bands/band2/ops",
            "mallory",
            KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("x1", "phone", 1, 1, CreateEvent("ev1", "Cancelled, refunds at evil.example", LocalDate(2026, 1, 1)))))),
        )
        assertEquals(200, spoof.status, spoof.body)
        assertEquals("Wesele &quot;Kowalskich&quot; &lt;3", call("GET", "/e/ev1").title())
    }

    @Test
    fun aDeletedEventsPointerGoes() {
        setup()
        push(DeleteEvent("ev1"))
        assertNull(store.get(StorageKeys.eventPointer("ev1")))
    }

    @Test
    fun theRealPageGetsTheCard() {
        val real = java.io.File("../composeApp/src/wasmJsMain/resources/index.html").readText()
        val html = LinkPages.withCard(real, "Gig", "14.11.2026")
        assertTrue("""<meta property="og:title" content="Gig">""" in html)
        assertTrue("""<meta property="og:description" content="14.11.2026">""" in html)
        assertEquals(1, Regex("property=\"og:title\"").findAll(html).count())
    }

    @Test
    fun aPageWithoutTheTagsStillGetsTheCard() {
        val html = LinkPages.withCard("<html><head><title>x</title></head></html>", "Gig", null)
        assertTrue("""<meta property="og:title" content="Gig">""" in html && html.indexOf("og:title") < html.indexOf("</head>"))
    }

    @Test
    fun withoutThePageCloudFrontShowsThePlainOne() {
        setup()
        index = null
        assertEquals(404, call("GET", "/e/ev1").status)
    }
}
