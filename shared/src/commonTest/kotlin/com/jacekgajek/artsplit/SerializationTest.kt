package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.UpdateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerializationTest {
    @Test
    fun bandRoundTrips() {
        val band = Scenario("Alice", "Bob").event("gig", "Alice", "Bob", guests = listOf("Helper" to 0))
            .apply(AddHolding("gig", "h1", "alice", pln("100.50"), IncomeSource.HAT, "hat"))
            .apply(AddCost("c1", "bob", pln("20"), DATE, incurredForEventId = "gig"))
            .band
        val json = ArtSplitJson.encodeToString(Band.serializer(), band)
        assertEquals(band, ArtSplitJson.decodeFromString(Band.serializer(), json))
        assertTrue("\"amount\":10050" in json, json)
    }

    @Test
    fun operationsUseTypeDiscriminator() {
        val envelope = OpEnvelope("op1", "client", 1, 42, UpdateEvent("gig", location = Change(null), name = Change("New")))
        val json = ArtSplitJson.encodeToString(OpEnvelope.serializer(), envelope)
        assertTrue("\"type\":\"UpdateEvent\"" in json, json)
        assertEquals(envelope, ArtSplitJson.decodeFromString(OpEnvelope.serializer(), json))
    }
}
