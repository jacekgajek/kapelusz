package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.ops.RenameBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement

/** The lenient reading of history entries (I3 R12). */
class HistoryDecoderTest {
    private fun json(text: String): JsonElement = KapeluszJson.parseToJsonElement(text)

    @Test
    fun anEntryWithoutWhoOrWhenIsSkipped() {
        assertNull(HistoryDecoder.item(json("""{"at":5,"ops":[]}""")))
        assertNull(HistoryDecoder.item(json("""{"userId":"alice","ops":[]}""")))
        assertNull(HistoryDecoder.item(json("""[1,2]""")))
    }

    @Test
    fun anUnreadableChangeOnlyLosesItsUndo() {
        val item = HistoryDecoder.item(
            json(
                """{"userId":"alice","at":5,"id":"2026-10/x.json","futureField":1,
                   "ops":[{"opId":"o1","clientId":"c","seq":1,"timestamp":5,"op":{"type":"RenameBand","name":"Owls"}}],
                   "changes":{"o1":{"unexpected":[1,2,3],"bandFields":"not an object"}}}""",
            ),
        )!!
        assertEquals("2026-10/x.json", item.entry.id)
        assertEquals(null, item.entry.memberId)
        assertEquals(listOf(RenameBand("Owls")), item.entry.ops.map { it.op })
        assertTrue(item.entry.changes.isEmpty(), "the change cannot be read, so it cannot be undone here")
    }

    @Test
    fun anUnknownOperationKeepsItsPlaceAndEvent() {
        val item = HistoryDecoder.item(json("""{"userId":"alice","at":5,"ops":[{"op":{"type":"Future","eventId":"e1"}}, "garbage"]}"""))!!
        assertEquals(listOf(UnreadableOp("5-0", "e1"), UnreadableOp("5-1", null)), item.unreadable, "ids from the entry's time when missing")
    }

    @Test
    fun aBrokenPageIsEmptyAndNamesTheAskedMonth() {
        val page = HistoryDecoder.page(json("""{"entries":"nope"}"""), "2026-10")
        assertEquals(HistoryPage("2026-10", emptyList(), null), page)
    }
}
