package com.jacekgajek.kapelusz.wire

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UnknownOperation
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer

/**
 * The stored and transmitted JSON must not change while the code is restructured (REFACTORING.md, step 0.2): bands
 * in S3, operations in the apps' queues and the history in DynamoDB are all read by this code. [WireFixtures] holds
 * the JSON the code produced before the refactoring; these tests fail on any byte that differs.
 *
 * Regenerating the fixtures is a deliberate act: run [WireFixtureDump] and copy its output. Do it only for a change
 * of the format itself, never to make a refactoring step pass.
 */
class WireFormatTest :
    ExpectSpec({
        expect("every operation encodes as before") {
            withClue("one fixture per operation kind") { WireSamples.everyOperation.size shouldBe WireFixtures.OPERATIONS.size }
            WireSamples.everyOperation.forEachIndexed { i, op ->
                val json = KapeluszJson.encodeToString(Operation.serializer(), op)
                withClue("operation $i (${op::class.simpleName})") { json shouldBe WireFixtures.OPERATIONS[i] }
            }
        }

        expect("every operation decodes from before") {
            WireSamples.everyOperation.forEachIndexed { i, op ->
                withClue("operation $i") {
                    KapeluszJson.decodeFromString(Operation.serializer(), WireFixtures.OPERATIONS[i]) shouldBe op
                }
            }
        }

        expect("the operation log encodes as before") {
            KapeluszJson.encodeToString(ListSerializer(OpEnvelope.serializer()), WireSamples.log) shouldBe WireFixtures.LOG
            KapeluszJson.decodeFromString(ListSerializer(OpEnvelope.serializer()), WireFixtures.LOG) shouldBe WireSamples.log
        }

        expect("replaying the log gives the band stored before") {
            val band = WireSamples.storedBand()
            KapeluszJson.encodeToString(Band.serializer(), band) shouldBe WireFixtures.BAND
            KapeluszJson.decodeFromString(Band.serializer(), WireFixtures.BAND) shouldBe band
        }

        expect("a history entry encodes as before") {
            KapeluszJson.encodeToString(HistoryEntry.serializer(), WireSamples.historyEntry) shouldBe WireFixtures.HISTORY_ENTRY
            KapeluszJson.decodeFromString(HistoryEntry.serializer(), WireFixtures.HISTORY_ENTRY) shouldBe WireSamples.historyEntry
        }

        expect("reading is lenient") {
            // A field from a newer version is ignored; an enum value it does not know becomes the field's default.
            val cost =
                """{"type":"AddCost","costId":"c1","memberId":"bob","amount":100""" +
                    ""","date":"2026-09-13","category":"NEW_KIND","extra":1}"""
            KapeluszJson.decodeFromString(Operation.serializer(), cost) shouldBe AddCost("c1", "bob", Money(100), WireSamples.date)
        }

        expect("an operation of an unknown kind decodes as unknown") {
            val json = """{"type":"MoveMember","memberId":"bob","eventId":"gig"}"""
            KapeluszJson.decodeFromString(Operation.serializer(), json) shouldBe UnknownOperation("gig")
            KapeluszJson.decodeFromString(Operation.serializer(), """{"type":"Whatever"}""") shouldBe UnknownOperation()
        }
    })
