package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.Undo
import kotlin.test.Test
import kotlin.test.assertEquals

/** ITERATION_4 §2.3: a default share per member. */
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
    fun itIsChecked() {
        val scenario = Scenario("Alice", "Bob").event("old", "Alice", "Bob").apply(SetEventArchived("old", true))
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(SetMemberShare("bob", 75, null, events = listOf("old"))).reason)
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetMemberShare("bob", -1, null)).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetMemberShare("bob", 100, pln("0"))).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetMemberShare("bob", 100, Money(Limits.MAX_AMOUNT + 1))).reason)
        assertEquals(RejectionReason.NOT_FOUND, scenario.rejected(SetMemberShare("nobody", 100, null)).reason)
    }
}
