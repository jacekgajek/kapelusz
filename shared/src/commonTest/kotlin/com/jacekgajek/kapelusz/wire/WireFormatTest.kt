package com.jacekgajek.kapelusz.wire

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UnknownOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer

/**
 * The stored and transmitted JSON must not change while the code is restructured (REFACTORING.md, step 0.2): bands
 * in S3, operations in the apps' queues and the history in DynamoDB are all read by this code. [WireFixtures] holds
 * the JSON the code produced before the refactoring; these tests fail on any byte that differs.
 *
 * Regenerating the fixtures is a deliberate act: run [WireFixtureDump] and copy its output. Do it only for a change
 * of the format itself, never to make a refactoring step pass.
 */
class WireFormatTest {
    @Test
    fun everyOperationEncodesAsBefore() {
        assertEquals(WireFixtures.OPERATIONS.size, WireSamples.everyOperation.size, "one fixture per operation kind")
        WireSamples.everyOperation.forEachIndexed { i, op ->
            val json = KapeluszJson.encodeToString(Operation.serializer(), op)
            assertEquals(WireFixtures.OPERATIONS[i], json, "operation $i (${op::class.simpleName})")
        }
    }

    @Test
    fun everyOperationDecodesFromBefore() {
        WireSamples.everyOperation.forEachIndexed { i, op ->
            assertEquals(op, KapeluszJson.decodeFromString(Operation.serializer(), WireFixtures.OPERATIONS[i]), "operation $i")
        }
    }

    @Test
    fun theOperationLogEncodesAsBefore() {
        assertEquals(WireFixtures.LOG, KapeluszJson.encodeToString(ListSerializer(OpEnvelope.serializer()), WireSamples.log))
        assertEquals(WireSamples.log, KapeluszJson.decodeFromString(ListSerializer(OpEnvelope.serializer()), WireFixtures.LOG))
    }

    @Test
    fun replayingTheLogGivesTheBandStoredBefore() {
        val band = WireSamples.storedBand()
        assertEquals(WireFixtures.BAND, KapeluszJson.encodeToString(Band.serializer(), band))
        assertEquals(band, KapeluszJson.decodeFromString(Band.serializer(), WireFixtures.BAND))
    }

    @Test
    fun aHistoryEntryEncodesAsBefore() {
        assertEquals(WireFixtures.HISTORY_ENTRY, KapeluszJson.encodeToString(HistoryEntry.serializer(), WireSamples.historyEntry))
        assertEquals(WireSamples.historyEntry, KapeluszJson.decodeFromString(HistoryEntry.serializer(), WireFixtures.HISTORY_ENTRY))
    }

    @Test
    fun readingIsLenient() {
        // A field from a newer version is ignored; an enum value it does not know becomes the field's default.
        val cost = """{"type":"AddCost","costId":"c1","memberId":"bob","amount":100,"date":"2026-09-13","category":"NEW_KIND","extra":1}"""
        assertEquals(AddCost("c1", "bob", Money(100), WireSamples.date), KapeluszJson.decodeFromString(Operation.serializer(), cost))
    }

    @Test
    fun anOperationOfAnUnknownKindDecodesAsUnknown() {
        val json = """{"type":"MoveMember","memberId":"bob","eventId":"gig"}"""
        assertEquals(UnknownOperation("gig"), KapeluszJson.decodeFromString(Operation.serializer(), json))
        assertEquals(UnknownOperation(), KapeluszJson.decodeFromString(Operation.serializer(), """{"type":"Whatever"}"""))
    }
}
