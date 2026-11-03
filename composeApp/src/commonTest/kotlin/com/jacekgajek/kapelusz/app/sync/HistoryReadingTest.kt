package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.ops.RenameBand
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class HistoryReadingTest :
    ExpectSpec({
        coroutineTestScope = true

        // One push with an operation this version knows and one it does not (from a newer app), plus a broken entry.
        val page = """
        {"month":"2026-10","next":"cursor","entries":[
          {"userId":"alice","memberId":"m1","at":5,"ops":[
            {"opId":"o1","clientId":"c","seq":1,"timestamp":5,"op":{"type":"RenameBand","name":"Night Owls"}},
            {"opId":"o2","clientId":"c","seq":2,"timestamp":5,"op":{"type":"SetEventAgenda","eventId":"e1","times":[]}}
          ]},
          {"broken":true}
        ]}
        """.trimIndent()

        expect("a change from a newer version does not fail the page") {
            val client = HttpClient(MockEngine { respond(page, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
            val history = ApiClient(client, "https://api.test") { "alice:0" }.history("band", "2026-10")
            history.next shouldBe "cursor"
            val item = history.entries.single()
            item.entry.ops.map { it.op } shouldBe listOf(RenameBand("Night Owls"))
            withClue("kept, with its event, so the event screen lists it") { item.unreadable shouldBe listOf(UnreadableOp("o2", "e1")) }
            withClue("in order") {
                item.ops.map { (it as? ReadOp.Known)?.envelope?.opId ?: (it as ReadOp.Unknown).op.opId } shouldBe listOf("o1", "o2")
            }
        }
    })
