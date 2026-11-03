package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.view.BandView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** A default share per member. */
class MemberShareTest :
    ExpectSpec({

        expect("leads set it and it is where a new participant starts") {
            val scenario = Scenario("Alice", "Bob", "Carol").event("gig", "Alice")
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.userFor("carol", Role.LEAD).let { carol -> scenario.apply(SetMemberShare("bob", 50, null), carol) }
            scenario.rejected(SetMemberShare("bob", 200, null), bob).reason shouldBe RejectionReason.ROLE_REQUIRED
            val member = scenario.band.member("bob")!!
            member.shareWeight shouldBe 50
            // The app adds them with it.
            scenario.apply(AddParticipant("gig", "bob", member.shareWeight, member.shareFixed))
            scenario.band.event("gig")!!.participant("bob")!!.weight shouldBe 50
            scenario.apply(SetMemberShare("carol", 100, pln("300"))).apply(AddParticipant("gig", "carol", 100, pln("300")))
            scenario.band.event("gig")!!.participant("carol")!!.fixedShare shouldBe pln("300")
        }

        expect("changing it touches only the events chosen") {
            val scenario = Scenario("Alice", "Bob").event("a", "Alice", "Bob").event("b", "Alice", "Bob")
            val before = scenario.band
            val op = SetMemberShare("bob", 75, null, events = listOf("a"))
            scenario.apply(op)
            scenario.band.event("a")!!.participant("bob")!!.weight shouldBe 75
            scenario.band.event("b")!!.participant("bob")!!.weight shouldBe 100
            // One change to undo: the default and the event together.
            scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)))
            scenario.band.member("bob")!!.shareWeight shouldBe 100
            scenario.band.event("a")!!.participant("bob")!!.weight shouldBe 100
        }

        expect("events that changed meanwhile are left alone") {
            // Chosen on a phone, then archived, settled or left elsewhere before the change arrived: the default still counts.
            val scenario = Scenario("Alice", "Bob", "Carol")
                .event("old", "Alice", "Bob").event("done", "Alice", "Bob").event("other", "Alice")
                .apply(AddHolding("done", "h", "alice", pln("100"), DOOR)).apply(SettleSuggestions("done"))
                .apply(SetEventArchived("old", true))
            scenario.apply(SetMemberShare("bob", 75, null, events = listOf("old", "done", "other", "gone", "done")))
            scenario.band.member("bob")!!.shareWeight shouldBe 75
            scenario.band.event("old")!!.participant("bob")!!.weight shouldBe 100
            scenario.band.event("done")!!.participant("bob")!!.weight shouldBe 100
        }

        expect("a fixed default matches by amount and keeps the percentage underneath") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
                .apply(SetWeight("gig", "bob", 75)).apply(SetFixedShare("gig", "bob", pln("300")))
                .apply(SetMemberShare("bob", 100, pln("300")))
            val member = scenario.band.member("bob")!!
            withClue("the same 300, whatever the hidden percentage") {
                MemberShares.stillDefault(BandView(scenario.band), member).map { it.id } shouldBe listOf("gig")
            }
            scenario.apply(SetMemberShare("bob", 100, pln("400"), events = listOf("gig")))
            val bob = scenario.band.event("gig")!!.participant("bob")!!
            bob.fixedShare shouldBe pln("400")
            withClue("back to percent would restore 75%") { bob.weight shouldBe 75 }
        }

        expect("it is checked") {
            val scenario = Scenario("Alice", "Bob").apply(SetMemberArchived("bob", true))
            scenario.rejected(SetMemberShare("bob", 75, null)).reason shouldBe RejectionReason.ARCHIVED_MEMBER
            scenario.apply(SetMemberArchived("bob", false))
            scenario.rejected(SetMemberShare("bob", -1, null)).reason shouldBe RejectionReason.INVALID_SHARE
            scenario.rejected(SetMemberShare("bob", 100, pln("0"))).reason shouldBe RejectionReason.INVALID_AMOUNT
            scenario.rejected(SetMemberShare("bob", 100, Money(Limits.MAX_AMOUNT + 1))).reason shouldBe RejectionReason.INVALID_AMOUNT
            scenario.rejected(SetMemberShare("nobody", 100, null)).reason shouldBe RejectionReason.NOT_FOUND
        }
    })
