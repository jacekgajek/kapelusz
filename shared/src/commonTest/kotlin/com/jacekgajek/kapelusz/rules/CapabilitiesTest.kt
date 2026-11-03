package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

class CapabilitiesTest :
    ExpectSpec({

        fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
            (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
        }

        val band = Band.create("b", "Band", "PLN", "owner", "Owner", "u-owner").apply(
            AddMember("lead", "Lead"),
            LinkUser("lead", "u-lead", Role.LEAD),
            AddMember("member", "Member"),
            LinkUser("member", "u-member", Role.MEMBER),
            AddMember("viewer", "Viewer"),
            LinkUser("viewer", "u-viewer", Role.VIEWER),
            CreateEvent("e", "Gig", LocalDate(2026, 9, 13), participants = listOf(Participant("lead"), Participant("member"))),
        )
        val event = band.event("e")!!

        expect("answers follow the role matrix") {
            val lead = Capabilities(band, "u-lead")
            val member = Capabilities(band, "u-member")
            val viewer = Capabilities(band, "u-viewer")

            lead.createEvent() shouldBe true
            member.createEvent() shouldBe false
            lead.settleAll(event) shouldBe true
            member.settleAll(event) shouldBe false

            lead.holdingParties(event) shouldBe listOf("lead", "member")
            member.holdingParties(event) shouldBe listOf("member")
            viewer.holdingParties(event).shouldBeEmpty()

            member.addCost() shouldBe true
            viewer.addCost() shouldBe false
            withClue("leaders manage the band's members") { lead.manageRoster() shouldBe true }
            member.manageRoster() shouldBe false
            lead.transferOwnership() shouldBe false
            Capabilities(band, "u-owner").transferOwnership() shouldBe true
        }

        // "Add cost" in an event: a lead adds for any participant, a member only when taking part themselves.
        expect("adding a cost in an event needs someone in it") {
            val other = band.apply(CreateEvent("o", "Other gig", LocalDate(2026, 9, 14), participants = listOf(Participant("owner"))))
            Capabilities(other, "u-lead").addCostIn(other.event("e")!!) shouldBe true
            Capabilities(other, "u-member").addCostIn(other.event("e")!!) shouldBe true
            withClue("a lead adds it for the owner") { Capabilities(other, "u-lead").addCostIn(other.event("o")!!) shouldBe true }
            withClue("not in it") { Capabilities(other, "u-member").addCostIn(other.event("o")!!) shouldBe false }
            Capabilities(other, "u-viewer").addCostIn(other.event("e")!!) shouldBe false
        }
    })
