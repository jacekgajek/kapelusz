package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {

    private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob")

    @Test
    fun costIncurredForEventIsPreIncludedOnlyForParticipants() {
        val scenario = gig()
            .apply(AddCost("bob-cost", "bob", pln("40"), DATE, incurredForEventId = "gig"))
        assertEquals(listOf(Refund("bob-cost", pln("40"))), scenario.band.event("gig")!!.refunds)
        // Only for an event its owner takes part in (ITERATION_3 R2).
        assertEquals(RejectionReason.NOT_A_PARTICIPANT, scenario.rejected(AddCost("carol-cost", "carol", pln("25"), DATE, incurredForEventId = "gig")).reason)

        scenario.apply(AddParticipant("gig", "carol"))
        scenario.apply(AddCost("carol-cost", "carol", pln("25"), DATE, incurredForEventId = "gig"))
        assertEquals(pln("25"), scenario.band.event("gig")!!.refundOf("carol-cost")!!.amount)
    }

    @Test
    fun editingCostAmountKeepsFullRefundAtOwnEvent() {
        val scenario = gig()
            .apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"))
            .apply(UpdateCost("c", amount = Change(pln("55"))))
        assertEquals(pln("55"), scenario.band.event("gig")!!.refundOf("c")!!.amount)
    }

    @Test
    fun costCannotDropBelowRefunded() {
        val scenario = gig()
            .apply(AddCost("c", "bob", pln("40"), DATE))
            .apply(SetRefund("gig", "c", pln("30")))
        assertEquals(RejectionCode.INVALID, scenario.rejected(UpdateCost("c", amount = Change(pln("20")))).code)
    }

    @Test
    fun refundRules() {
        val scenario = gig().apply(AddCost("c", "carol", pln("40"), DATE)).apply(AddCost("b", "bob", pln("40"), DATE))
        assertEquals(RejectionCode.INVALID, scenario.rejected(SetRefund("gig", "c", pln("10"))).code, "non-participant")
        assertEquals(RejectionCode.INVALID, scenario.rejected(SetRefund("gig", "b", pln("41"))).code, "exceeds")
        scenario.apply(SetRefund("gig", "b", pln("40"))).apply(SetRefund("gig", "b", Money.ZERO))
        assertTrue(scenario.band.event("gig")!!.refunds.isEmpty())
    }

    @Test
    fun deletingCostRemovesItsRefunds() {
        val scenario = gig().apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig")).apply(DeleteCost("c"))
        assertTrue(scenario.band.event("gig")!!.refunds.isEmpty())
    }

    @Test
    fun eventWithTransfersCannotBeDeleted() {
        val scenario = gig()
            .apply(AddHolding("gig", "h", "alice", pln("100"), DOOR))
            .apply(RecordTransfer("gig", "t", "alice", "bob", pln("50")))
        assertEquals(RejectionCode.CONFLICT, scenario.rejected(DeleteEvent("gig")).code)
    }

    @Test
    fun deletingEventDetachesItsCosts() {
        val scenario = gig().apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig")).apply(DeleteEvent("gig"))
        assertNull(scenario.band.cost("c")!!.eventId)
    }

    @Test
    fun participantWithEntriesCannotBeRemoved() {
        val scenario = gig().apply(AddHolding("gig", "h", "bob", pln("100"), DOOR))
        val rejection = scenario.rejected(RemoveParticipant("gig", "bob"))
        assertEquals(RejectionCode.CONFLICT, rejection.code)
        assertEquals(RejectionReason.PARTICIPANT_HAS_MONEY, rejection.reason)
        scenario.apply(RemoveParticipant("gig", "alice"))
        assertNull(scenario.band.event("gig")!!.participant("alice"))
    }

    @Test
    fun fundRequiresTreasurer() {
        assertEquals(RejectionCode.INVALID, gig().rejected(SetFundAmount("gig", pln("10"))).code)
    }

    @Test
    fun memberWithHistoryCannotBeDeleted() {
        val scenario = gig()
        assertEquals(RejectionCode.CONFLICT, scenario.rejected(DeleteMember("bob")).code)
        scenario.apply(DeleteMember("carol"))
        assertNull(scenario.band.member("carol"))
    }

    @Test
    fun ownershipTransfer() {
        val scenario = gig()
        scenario.userFor("bob", Role.LEAD)
        assertEquals(RejectionCode.INVALID, scenario.rejected(SetMemberRole("alice", Role.LEAD)).code)
        scenario.apply(TransferOwnership("bob"))
        assertEquals(Role.OWNER, scenario.band.member("bob")!!.role)
        assertEquals(Role.LEAD, scenario.band.member("alice")!!.role)
    }

    @Test
    fun eventUpdatesArePatchesAndStampUpdatedAt() {
        val scenario = gig().apply(UpdateEvent("gig", location = Change("Hall")))
        val before = scenario.band.event("gig")!!
        scenario.apply(UpdateEvent("gig", notes = Change("bring cables")))
        val after = scenario.band.event("gig")!!
        assertEquals("Hall", after.location)
        assertEquals("bring cables", after.notes)
        assertTrue(after.updatedAt > before.updatedAt)
    }

    @Test
    fun batchIsIdempotentAndRejectionsAdvanceSequence() {
        val band = gig().band
        val owner = Actor.User("user-alice")
        val batch = listOf(
            OpEnvelope("a", "phone", 1, 1, RenameBand("New name")),
            OpEnvelope("b", "phone", 2, 2, DeleteEvent("missing")),
            OpEnvelope("c", "phone", 3, 3, AddCost("c", "bob", pln("10"), DATE)),
        )
        val first = Engine.applyBatch(band, owner, batch)
        assertEquals(listOf("a", "c"), first.accepted)
        assertEquals(RejectionCode.NOT_FOUND, first.rejected.getValue("b").code)
        assertEquals(3L, first.band.lastSeq["phone"])

        val retry = Engine.applyBatch(first.band, owner, batch + OpEnvelope("d", "phone", 4, 4, RenameBand("Newer")))
        assertEquals(listOf("a", "c"), retry.duplicates)
        assertEquals(RejectionCode.NOT_FOUND, retry.rejected.getValue("b").code, "a retried refusal is reported again")
        assertEquals(listOf("d"), retry.accepted)
        assertEquals(1, retry.band.costs.size)
        assertEquals("Newer", retry.band.name)
    }

    @Test
    fun guestIdCannotShadowMember() {
        val op = CreateEvent("x", "x", DATE, guests = listOf(Guest("bob", "Fake")), participants = listOf(Participant("bob")))
        assertEquals(RejectionCode.INVALID, gig().rejected(op).code)
    }

    @Test
    fun movingCostToAnotherEventPreIncludesItThere() {
        val scenario = gig().event("other-gig", "Bob")
            .apply(AddCost("c", "bob", pln("40"), DATE))
            .apply(UpdateCost("c", incurredForEventId = Change("other-gig")))
        assertEquals(pln("40"), scenario.band.event("other-gig")!!.refundOf("c")!!.amount)
        assertNull(scenario.band.event("gig")!!.refundOf("c"))
    }

    @Test
    fun costChangesStampTheEventsTheyRefund() {
        val scenario = gig()
        val created = scenario.band.event("gig")!!.updatedAt
        scenario.apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"))
        val afterAdd = scenario.band.event("gig")!!.updatedAt
        assertTrue(afterAdd > created)
        scenario.apply(DeleteCost("c"))
        assertTrue(scenario.band.event("gig")!!.updatedAt > afterAdd)
    }

    @Test
    fun aMembersCostDoesNotReopenASettledOrArchivedEvent() {
        val scenario = gig().apply(com.jacekgajek.kapelusz.ops.AddHolding("gig", "h", "alice", pln("100"), com.jacekgajek.kapelusz.model.IncomeSource.HAT, null))
            .apply(com.jacekgajek.kapelusz.ops.SettleSuggestions("gig"))
        scenario.apply(AddCost("late", "bob", pln("30"), DATE, incurredForEventId = "gig"))
        assertEquals(null, scenario.band.event("gig")!!.refundOf("late"), "money moved: a lead decides")
        scenario.apply(com.jacekgajek.kapelusz.ops.SetEventArchived("gig", true))
        scenario.apply(AddCost("later", "bob", pln("20"), DATE))
        // Archived events are read-only (ITERATION_3 R10): not even a cost can be assigned to one.
        assertEquals(
            com.jacekgajek.kapelusz.ops.RejectionReason.ARCHIVED_EVENT,
            scenario.rejected(UpdateCost("later", incurredForEventId = Change("gig"))).reason,
        )
    }

    @Test
    fun aMembersAmountChangeDoesNotRaiseTheRefundOfASettledEvent() {
        val scenario = gig().apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"))
            .apply(com.jacekgajek.kapelusz.ops.AddHolding("gig", "h", "alice", pln("100"), com.jacekgajek.kapelusz.model.IncomeSource.HAT, null))
            .apply(com.jacekgajek.kapelusz.ops.SettleSuggestions("gig"))
        scenario.apply(UpdateCost("c", amount = Change(pln("60"))))
        assertEquals(pln("40"), scenario.band.event("gig")!!.refundOf("c")!!.amount, "settled: the refund stays; a lead decides")
    }
}
