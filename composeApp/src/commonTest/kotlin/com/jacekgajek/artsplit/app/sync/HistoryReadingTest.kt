package com.jacekgajek.artsplit.app.sync

import com.jacekgajek.artsplit.ops.RenameBand
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryReadingTest {
    // One push with an operation this version knows and one it does not (from a newer app), plus a broken entry.
    private val page = """
        {"month":"2026-10","next":"cursor","entries":[
          {"userId":"alice","memberId":"m1","at":5,"ops":[
            {"opId":"o1","clientId":"c","seq":1,"timestamp":5,"op":{"type":"RenameBand","name":"Night Owls"}},
            {"opId":"o2","clientId":"c","seq":2,"timestamp":5,"op":{"type":"SetEventAgenda","eventId":"e1","times":[]}}
          ]},
          {"broken":true}
        ]}
    """.trimIndent()

    @Test
    fun aChangeFromANewerVersionDoesNotFailThePage() = runTest {
        val client = HttpClient(MockEngine { respond(page, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val history = ApiClient(client, "https://api.test") { "alice:0" }.history("band", "2026-10")
        assertEquals("cursor", history.next)
        val item = history.entries.single()
        assertEquals(listOf(RenameBand("Night Owls")), item.entry.ops.map { it.op })
        assertEquals(listOf(UnreadableOp("o2", "e1")), item.unreadable, "kept, with its event, so the event screen lists it")
    }
}
