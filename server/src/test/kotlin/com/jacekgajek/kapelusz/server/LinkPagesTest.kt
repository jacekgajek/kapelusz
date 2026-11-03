package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.server.links.LinkPages
import com.jacekgajek.kapelusz.server.links.WebIndex
import com.jacekgajek.kapelusz.server.store.StorageKeys
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

private class LinkPagesApi : ApiHarness(webIndex = WebPage()) {
    val web get() = webIndex as WebPage

    fun setup() {
        createBand()
        push("alice", CreateEvent("ev1", "Wesele \"Kowalskich\" <3", LocalDate(2026, 11, 14)))
    }

    fun HttpResponse.title() = Regex("""og:title" content="([^"]*)"""").find(body.orEmpty())?.groupValues?.get(1)

    fun HttpResponse.description() = Regex("""og:description" content="([^"]*)"""").find(body.orEmpty())?.groupValues?.get(1)
}

class LinkPagesTest :
    ExpectSpec({

        expect("an event link shows its name and date only") {
            with(LinkPagesApi()) {
                setup()
                val response = call("GET", "/e/ev1", null)
                response.status shouldBe 200
                response.headers["content-type"] shouldBe "text/html; charset=utf-8"
                withClue("escaped") { response.title() shouldBe "Wesele &quot;Kowalskich&quot; &lt;3" }
                response.description() shouldBe "14.11.2026"
                withClue("the rest of the page as it is") {
                    response.body.orEmpty() shouldContain "og:image"
                    response.body.orEmpty() shouldContain "<body></body>"
                }
                call("HEAD", "/e/ev1", null).body.shouldBeNull()
            }
        }

        expect("anything else keeps the generic card") {
            with(LinkPagesApi()) {
                setup()
                call("GET", "/e/nope", null).title() shouldBe "Kapelusz · Pass the Hat"
                push("alice", DeleteEvent("ev1"))
                call("GET", "/e/ev1", null).title() shouldBe "Kapelusz · Pass the Hat"
                call("GET", "/e/nope", null).description().shouldBeNull()
                call("GET", "/e/a/b", null).status shouldBe 404
                call("POST", "/e/ev1", null).status shouldBe 404
            }
        }

        expect("another band cannot take over an event's card") {
            with(LinkPagesApi()) {
                setup()
                createBand("mallory", "band2", "Spoof")
                val spoof = call(
                    "POST",
                    "/bands/band2/ops",
                    "mallory",
                    KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("x1", "phone", 1, 1, CreateEvent("ev1", "Cancelled, refunds at evil.example", LocalDate(2026, 1, 1)))))),
                )
                withClue(spoof.body) { spoof.status shouldBe 200 }
                call("GET", "/e/ev1", null).title() shouldBe "Wesele &quot;Kowalskich&quot; &lt;3"
            }
        }

        expect("a deleted event's pointer goes") {
            with(LinkPagesApi()) {
                setup()
                push("alice", DeleteEvent("ev1"))
                store.get(StorageKeys.eventPointer("ev1")).shouldBeNull()
            }
        }

        expect("the real page gets the card") {
            val real = java.io.File("../composeApp/src/wasmJsMain/resources/index.html").readText()
            val html = LinkPages.withCard(real, "Gig", "14.11.2026")
            html shouldContain """<meta property="og:title" content="Gig">"""
            html shouldContain """<meta property="og:description" content="14.11.2026">"""
            Regex("property=\"og:title\"").findAll(html).count() shouldBe 1
        }

        expect("a page without the tags still gets the card") {
            val html = LinkPages.withCard("<html><head><title>x</title></head></html>", "Gig", null)
            html shouldContain """<meta property="og:title" content="Gig">"""
            html.indexOf("og:title") shouldBeLessThan html.indexOf("</head>")
        }

        expect("without the page CloudFront shows the plain one") {
            with(LinkPagesApi()) {
                setup()
                web.index = null
                call("GET", "/e/ev1", null).status shouldBe 404
            }
        }
    })
