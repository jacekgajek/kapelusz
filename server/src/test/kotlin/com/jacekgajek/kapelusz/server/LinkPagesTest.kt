package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** The web app's page as the server reads it; [index] null: it cannot be read. */
private class WebPage : WebIndex {
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
    var index: String? = page

    override fun html() = index
}

class LinkPagesTest : ApiHarness(webIndex = WebPage()) {
    private val web get() = webIndex as WebPage

    private fun setup() {
        createBand()
        push("alice", CreateEvent("ev1", "Wesele \"Kowalskich\" <3", LocalDate(2026, 11, 14)))
    }

    private fun HttpResponse.title() = Regex("""og:title" content="([^"]*)"""").find(body.orEmpty())?.groupValues?.get(1)
    private fun HttpResponse.description() = Regex("""og:description" content="([^"]*)"""").find(body.orEmpty())?.groupValues?.get(1)

    @Test
    fun anEventLinkShowsItsNameAndDateOnly() {
        setup()
        val response = call("GET", "/e/ev1", null)
        assertEquals(200, response.status)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertEquals("Wesele &quot;Kowalskich&quot; &lt;3", response.title(), "escaped")
        assertEquals("14.11.2026", response.description())
        assertTrue("og:image" in response.body.orEmpty() && "<body></body>" in response.body.orEmpty(), "the rest of the page as it is")
        assertNull(call("HEAD", "/e/ev1", null).body)
    }

    @Test
    fun anythingElseKeepsTheGenericCard() {
        setup()
        assertEquals("Kapelusz · Pass the Hat", call("GET", "/e/nope", null).title())
        push("alice", DeleteEvent("ev1"))
        assertEquals("Kapelusz · Pass the Hat", call("GET", "/e/ev1", null).title())
        assertNull(call("GET", "/e/nope", null).description())
        assertEquals(404, call("GET", "/e/a/b", null).status)
        assertEquals(404, call("POST", "/e/ev1", null).status)
    }

    @Test
    fun anotherBandCannotTakeOverAnEventsCard() {
        setup()
        createBand("mallory", "band2", "Spoof")
        val spoof = call(
            "POST",
            "/bands/band2/ops",
            "mallory",
            KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("x1", "phone", 1, 1, CreateEvent("ev1", "Cancelled, refunds at evil.example", LocalDate(2026, 1, 1)))))),
        )
        assertEquals(200, spoof.status, spoof.body)
        assertEquals("Wesele &quot;Kowalskich&quot; &lt;3", call("GET", "/e/ev1", null).title())
    }

    @Test
    fun aDeletedEventsPointerGoes() {
        setup()
        push("alice", DeleteEvent("ev1"))
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
        web.index = null
        assertEquals(404, call("GET", "/e/ev1", null).status)
    }
}
