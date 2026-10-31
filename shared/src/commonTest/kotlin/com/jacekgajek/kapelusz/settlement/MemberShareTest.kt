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
import com.jacekgajek.kapelusz.settlement.MemberShares
import com.jacekgajek.kapelusz.view.BandView
import kotlin.test.Test
import kotlin.test.assertEquals

/** I4 §2.3: a default share per member. */
class MemberShareTest {

    @Test
    fun leadsSetItAndItIsWhereANewParticipantStarts() {
        val scenario = Scenario("Alice", "Bob", "Carol").event("gig", "Alice")
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.userFor("carol", Role.LEAD).let { carol -> scenario.apply(SetMemberShare("bob", 50, null), carol) }
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetMemberShare("bob", 200, null), bob).reason)
        val member = scenario.band.member("bob")!!
        assertEquals(50, member.shareWeight)
        // The app adds them with it.
        scenario.apply(AddParticipant("gig", "bob", member.shareWeight, member.shareFixed))
        assertEquals(50, scenario.band.event("gig")!!.participant("bob")!!.weight)
        scenario.apply(SetMemberShare("carol", 100, pln("300"))).apply(AddParticipant("gig", "carol", 100, pln("300")))
        assertEquals(pln("300"), scenario.band.event("gig")!!.participant("carol")!!.fixedShare)
    }

    @Test
    fun changingItTouchesOnlyTheEventsChosen() {
        val scenario = Scenario("Alice", "Bob").event("a", "Alice", "Bob").event("b", "Alice", "Bob")
        val before = scenario.band
        val op = SetMemberShare("bob", 75, null, events = listOf("a"))
        scenario.apply(op)
        assertEquals(75, scenario.band.event("a")!!.participant("bob")!!.weight)
        assertEquals(100, scenario.band.event("b")!!.participant("bob")!!.weight)
        // One change to undo: the default and the event together.
        scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)))
        assertEquals(100, scenario.band.member("bob")!!.shareWeight)
        assertEquals(100, scenario.band.event("a")!!.participant("bob")!!.weight)
    }

    @Test
    fun eventsThatChangedMeanwhileAreLeftAlone() {
        // Chosen on a phone, then archived, settled or left elsewhere before the change arrived: the default still counts.
        val scenario = Scenario("Alice", "Bob", "Carol").event("old", "Alice", "Bob").event("done", "Alice", "Bob").event("other", "Alice")
            .apply(AddHolding("done", "h", "alice", pln("100"), DOOR)).apply(SettleSuggestions("done"))
            .apply(SetEventArchived("old", true))
        scenario.apply(SetMemberShare("bob", 75, null, events = listOf("old", "done", "other", "gone", "done")))
        assertEquals(75, scenario.band.member("bob")!!.shareWeight)
        assertEquals(100, scenario.band.event("old")!!.participant("bob")!!.weight)
        assertEquals(100, scenario.band.event("done")!!.participant("bob")!!.weight)
    }

    @Test
    fun aFixedDefaultMatchesByAmountAndKeepsThePercentageUnderneath() {
        val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
            .apply(SetWeight("gig", "bob", 75)).apply(SetFixedShare("gig", "bob", pln("300")))
            .apply(SetMemberShare("bob", 100, pln("300")))
        val member = scenario.band.member("bob")!!
        assertEquals(
            listOf("gig"),
            MemberShares.stillDefault(BandView(scenario.band), member).map { it.id },
            "the same 300, whatever the hidden percentage",
        )
        scenario.apply(SetMemberShare("bob", 100, pln("400"), events = listOf("gig")))
        val bob = scenario.band.event("gig")!!.participant("bob")!!
        assertEquals(pln("400"), bob.fixedShare)
        assertEquals(75, bob.weight, "back to percent would restore 75%")
    }

    @Test
    fun itIsChecked() {
        val scenario = Scenario("Alice", "Bob").apply(SetMemberArchived("bob", true))
        assertEquals(RejectionReason.ARCHIVED_MEMBER, scenario.rejected(SetMemberShare("bob", 75, null)).reason)
        scenario.apply(SetMemberArchived("bob", false))
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetMemberShare("bob", -1, null)).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetMemberShare("bob", 100, pln("0"))).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetMemberShare("bob", 100, Money(Limits.MAX_AMOUNT + 1))).reason)
        assertEquals(RejectionReason.NOT_FOUND, scenario.rejected(SetMemberShare("nobody", 100, null)).reason)
    }
}
