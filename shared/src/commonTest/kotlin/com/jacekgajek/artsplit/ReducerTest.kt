package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.Guest
import com.jacekgajek.artsplit.model.IncomeSource.DOOR
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.Refund
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.AddGuest
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.AddParticipant
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.DeleteCost
import com.jacekgajek.artsplit.ops.DeleteEvent
import com.jacekgajek.artsplit.ops.DeleteMember
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.PromoteGuest
import com.jacekgajek.artsplit.ops.RecordTransfer
import com.jacekgajek.artsplit.ops.RejectionCode
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.RemoveParticipant
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.SetFundAmount
import com.jacekgajek.artsplit.ops.SetMemberRole
import com.jacekgajek.artsplit.ops.SetRefund
import com.jacekgajek.artsplit.ops.TransferOwnership
import com.jacekgajek.artsplit.ops.UpdateCost
import com.jacekgajek.artsplit.ops.UpdateEvent
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
            .apply(AddCost("carol-cost", "carol", pln("25"), DATE, incurredForEventId = "gig"))
        assertEquals(listOf(Refund("bob-cost", pln("40"))), scenario.band.event("gig")!!.refunds)

        scenario.apply(AddParticipant("gig", "carol"))
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
    fun promotedGuestKeepsEntries() {
        val scenario = gig()
            .apply(AddGuest("gig", "g", "Guest", 0))
            .apply(AddHolding("gig", "h", "g", pln("100"), DOOR))
            .apply(PromoteGuest("gig", "g", "new-member"))
        val event = scenario.band.event("gig")!!
        assertTrue(event.guests.isEmpty())
        assertEquals("new-member", event.holdings.single().partyId)
        assertEquals(0, event.participant("new-member")!!.weight)
        assertEquals("Guest", scenario.band.member("new-member")!!.name)
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
        scenario.userFor("bob", Role.ADMIN)
        assertEquals(RejectionCode.INVALID, scenario.rejected(SetMemberRole("alice", Role.LEAD)).code)
        scenario.apply(TransferOwnership("bob"))
        assertEquals(Role.OWNER, scenario.band.member("bob")!!.role)
        assertEquals(Role.ADMIN, scenario.band.member("alice")!!.role)
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
        assertEquals(listOf("a", "b", "c"), retry.duplicates)
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
}
