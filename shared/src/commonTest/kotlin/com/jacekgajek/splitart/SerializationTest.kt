package com.jacekgajek.splitart

import com.jacekgajek.splitart.model.SplitArtJson
import com.jacekgajek.splitart.model.Band
import com.jacekgajek.splitart.model.IncomeSource
import com.jacekgajek.splitart.ops.AddCost
import com.jacekgajek.splitart.ops.AddHolding
import com.jacekgajek.splitart.ops.Change
import com.jacekgajek.splitart.ops.OpEnvelope
import com.jacekgajek.splitart.ops.UpdateEvent
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
        val json = SplitArtJson.encodeToString(Band.serializer(), band)
        assertEquals(band, SplitArtJson.decodeFromString(Band.serializer(), json))
        assertTrue("\"amount\":10050" in json, json)
    }

    @Test
    fun operationsUseTypeDiscriminator() {
        val envelope = OpEnvelope("op1", "client", 1, 42, UpdateEvent("gig", location = Change(null), name = Change("New")))
        val json = SplitArtJson.encodeToString(OpEnvelope.serializer(), envelope)
        assertTrue("\"type\":\"UpdateEvent\"" in json, json)
        assertEquals(envelope, SplitArtJson.decodeFromString(OpEnvelope.serializer(), json))
    }
}
