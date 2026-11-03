package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.ops.RenameBand
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement

/** The lenient reading of history entries. */
class HistoryDecoderTest :
    ExpectSpec({
        fun json(text: String): JsonElement = KapeluszJson.parseToJsonElement(text)

        expect("an entry without who or when is skipped") {
            HistoryDecoder.item(json("""{"at":5,"ops":[]}""")).shouldBeNull()
            HistoryDecoder.item(json("""{"userId":"alice","ops":[]}""")).shouldBeNull()
            HistoryDecoder.item(json("""[1,2]""")).shouldBeNull()
        }

        expect("an unreadable change only loses its undo") {
            val item = HistoryDecoder.item(
                json(
                    """{"userId":"alice","at":5,"id":"2026-10/x.json","futureField":1,
                   "ops":[{"opId":"o1","clientId":"c","seq":1,"timestamp":5,"op":{"type":"RenameBand","name":"Owls"}}],
                   "changes":{"o1":{"unexpected":[1,2,3],"bandFields":"not an object"}}}""",
                ),
            ).shouldNotBeNull()
            item.entry.id shouldBe "2026-10/x.json"
            item.entry.memberId.shouldBeNull()
            item.entry.ops.map { it.op } shouldBe listOf(RenameBand("Owls"))
            withClue("the change cannot be read, so it cannot be undone here") { item.entry.changes.shouldBeEmpty() }
        }

        expect("an unknown operation keeps its place and event") {
            val item = HistoryDecoder.item(json("""{"userId":"alice","at":5,"ops":[{"op":{"type":"Future","eventId":"e1"}}, "garbage"]}"""))
                .shouldNotBeNull()
            withClue("ids from the entry's time when missing") {
                item.unreadable shouldBe listOf(UnreadableOp("5-0", "e1"), UnreadableOp("5-1", null))
            }
        }

        expect("a broken page is empty and names the asked month") {
            val page = HistoryDecoder.page(json("""{"entries":"nope"}"""), "2026-10")
            page shouldBe HistoryPage("2026-10", emptyList(), null)
        }
    })
