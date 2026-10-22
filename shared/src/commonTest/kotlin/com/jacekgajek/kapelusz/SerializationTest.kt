package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.UpdateEvent
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
        val json = KapeluszJson.encodeToString(Band.serializer(), band)
        assertEquals(band, KapeluszJson.decodeFromString(Band.serializer(), json))
        assertTrue("\"amount\":10050" in json, json)
    }

    @Test
    fun operationsUseTypeDiscriminator() {
        val envelope = OpEnvelope("op1", "client", 1, 42, UpdateEvent("gig", location = Change(null), name = Change("New")))
        val json = KapeluszJson.encodeToString(OpEnvelope.serializer(), envelope)
        assertTrue("\"type\":\"UpdateEvent\"" in json, json)
        assertEquals(envelope, KapeluszJson.decodeFromString(OpEnvelope.serializer(), json))
    }
}
