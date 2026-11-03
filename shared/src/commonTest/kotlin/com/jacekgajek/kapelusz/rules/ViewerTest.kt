package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** Viewers see everything but only watch: they take no part in events. */
class ViewerTest :
    ExpectSpec({

        expect("viewers are not added to events") {
            val scenario = Scenario("Alice", "Vera").event("gig", "Alice")
            scenario.userFor("vera", Role.VIEWER)
            scenario.rejected(AddParticipant("gig", "vera")).reason shouldBe RejectionReason.VIEWER_NOT_PARTICIPANT
            val op = CreateEvent("new", "new", DATE, participants = listOf(Participant("alice"), Participant("vera")))
            scenario.rejected(op).reason shouldBe RejectionReason.VIEWER_NOT_PARTICIPANT
        }

        // A viewer records no transfers, so they can't be left playing with money, or holding the band fund.
        expect("a viewer can't be left holding money or the fund") {
            val scenario = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob")
                .apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
            scenario.rejected(SetMemberRole("bob", Role.VIEWER)).reason shouldBe RejectionReason.VIEWER_STILL_PLAYS
            scenario.rejected(LinkUser("bob", "user-bob", Role.VIEWER)).reason shouldBe RejectionReason.VIEWER_STILL_PLAYS
            scenario.apply(SettleSuggestions("gig")).apply(LinkUser("bob", "user-bob", Role.VIEWER))
            withClue("settled: fine") { scenario.band.member("bob")!!.role shouldBe Role.VIEWER }
            scenario.rejected(SetTreasurer("bob")).reason shouldBe RejectionReason.VIEWER_NOT_PARTICIPANT
            scenario.apply(SetTreasurer("carol")).apply(LinkUser("carol", "user-carol", Role.MEMBER))
            withClue("the fund holder") {
                scenario.rejected(SetMemberRole("carol", Role.VIEWER)).reason shouldBe RejectionReason.VIEWER_STILL_PLAYS
            }
        }

        // Ownership never goes to an archived leader.
        expect("no archived owner") {
            val scenario = Scenario("Alice", "Bob").apply(LinkUser("bob", "user-bob", Role.LEAD)).apply(SetMemberArchived("bob", true))
            scenario.rejected(TransferOwnership("bob")).reason shouldBe RejectionReason.ARCHIVED_MEMBER
        }

        // A settle op whose id was seen before can't make two transfers with one id.
        expect("a repeated settle op id is refused") {
            val band = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
                .apply(AddHolding("gig", "h", "alice", pln("300"), DOOR)).band
            val first = (Engine.apply(band, Actor.System, SettleSuggestions("gig", "bob"), "same", 1) as ApplyResult.Applied).band
            val again = Engine.apply(first, Actor.System, SettleSuggestions("gig", "carol"), "same", 2)
            (again as ApplyResult.Rejected).rejection.reason shouldBe RejectionReason.DUPLICATE_ID
        }
    })
