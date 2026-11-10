package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetTreasurer
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
            listOfNotNull(band.member("lead"), band.member("member"), band.member("viewer")).forEach {
                lead.transferOwnershipTo(it) shouldBe false
            }
            Capabilities(band, "u-owner").transferOwnershipTo(band.member("lead")!!) shouldBe true
        }

        expect("the band's settings and its fund are the leads'") {
            val lead = Capabilities(band, "u-lead")
            val member = Capabilities(band, "u-member")
            lead.renameBand() shouldBe true
            member.renameBand() shouldBe false
            lead.setTreasurer() shouldBe true
            lead.setFundDefault() shouldBe true
            lead.setFundPayment() shouldBe true
            lead.editAgendaTemplate() shouldBe true
            member.editAgendaTemplate() shouldBe false
            withClue("one's own payment details") { member.setPayment(band.member("member")!!) shouldBe true }
            member.setPayment(band.member("lead")!!) shouldBe false

            withClue("the fund is off: nothing to add") {
                lead.addFundEntry() shouldBe false
                lead.setEventFund(event) shouldBe false
            }
            val withFund = band.apply(SetTreasurer("lead"))
            Capabilities(withFund, "u-lead").addFundEntry() shouldBe true
            Capabilities(withFund, "u-lead").setEventFund(withFund.event("e")!!) shouldBe true
            withClue("empty, so it may be turned off") { Capabilities(withFund, "u-lead").disableFund() shouldBe true }
            Capabilities(withFund, "u-member").setEventFund(withFund.event("e")!!) shouldBe false
        }

        expect("deleting a member: leads, never the owner or themselves, only who left no trace") {
            val lead = Capabilities(band, "u-lead")
            lead.deleteMember(band.member("viewer")!!) shouldBe true
            lead.deleteMember(band.member("owner")!!) shouldBe false
            lead.deleteMember(band.member("lead")!!) shouldBe false
            withClue("in an event: a trace") { lead.deleteMember(band.member("member")!!) shouldBe false }
            Capabilities(band, "u-member").deleteMember(band.member("viewer")!!) shouldBe false
        }

        expect("roles decide what is seen: costs of others, places, raw data, deleting the band") {
            val owner = Capabilities(band, "u-owner")
            owner.seeOthersCosts() shouldBe true
            owner.searchPlaces() shouldBe true
            owner.seeRawData() shouldBe true
            owner.deleteBand() shouldBe true

            val lead = Capabilities(band, "u-lead")
            lead.seeOthersCosts() shouldBe true
            lead.searchPlaces() shouldBe true
            lead.seeRawData() shouldBe true
            lead.deleteBand() shouldBe false

            val member = Capabilities(band, "u-member")
            member.viewer shouldBe false
            member.seeOthersCosts() shouldBe false
            member.searchPlaces() shouldBe false
            member.seeRawData() shouldBe false
            member.deleteBand() shouldBe false

            val viewer = Capabilities(band, "u-viewer")
            viewer.viewer shouldBe true
            withClue("a viewer sees everyone's anyway, so there is no switch for them") { viewer.seeOthersCosts() shouldBe false }
            viewer.searchPlaces() shouldBe false
            viewer.seeRawData() shouldBe false
            viewer.deleteBand() shouldBe false
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
