package com.jacekgajek.kapelusz.history

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.pln
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SerializationTest :
    ExpectSpec({
        expect("band round trips") {
            val band = Scenario("Alice", "Bob").event("gig", "Alice", "Bob", guests = listOf("Helper" to 0))
                .apply(AddHolding("gig", "h1", "alice", pln("100.50"), IncomeSource.HAT, "hat"))
                .apply(AddCost("c1", "bob", pln("20"), DATE, incurredForEventId = "gig"))
                .band
            val json = KapeluszJson.encodeToString(Band.serializer(), band)
            KapeluszJson.decodeFromString(Band.serializer(), json) shouldBe band
            withClue(json) { json shouldContain "\"amount\":10050" }
        }

        expect("operations use type discriminator") {
            val envelope = OpEnvelope("op1", "client", 1, 42, UpdateEvent("gig", location = Change(null), name = Change("New")))
            val json = KapeluszJson.encodeToString(OpEnvelope.serializer(), envelope)
            withClue(json) { json shouldContain "\"type\":\"UpdateEvent\"" }
            KapeluszJson.decodeFromString(OpEnvelope.serializer(), json) shouldBe envelope
        }
    })
