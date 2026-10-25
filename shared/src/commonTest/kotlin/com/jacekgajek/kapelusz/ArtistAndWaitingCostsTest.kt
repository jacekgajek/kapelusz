package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.UpdateCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ITERATION_4 §1.1 (artists) and §1.2 (costs wait for a lead's Refund here). */
class ArtistAndWaitingCostsTest {

    private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")

    @Test
    fun onlyALeadsCostEntryIsIncludedAutomatically() {
        val scenario = gig()
        val bob = scenario.userFor("bob", Role.MEMBER)
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.apply(AddCost("bobs", "bob", pln("40"), DATE, incurredForEventId = "gig"), bob)
        assertEquals(null, scenario.band.event("gig")!!.refundOf("bobs"), "a member's cost waits")
        assertEquals(listOf("bobs"), CostLedger.pendingFromEarlier(scenario.band, scenario.band.event("gig")!!).map { it.id })
        assertEquals(CostStatus.PENDING, CostLedger.status(scenario.band, scenario.band.cost("bobs")!!))

        scenario.apply(AddCost("for-carol", "carol", pln("30"), DATE, incurredForEventId = "gig"), alice)
        assertEquals(pln("30"), scenario.band.event("gig")!!.refundOf("for-carol")!!.amount, "a lead's entry, even for someone else")
        assertEquals(null, scenario.band.event("gig")!!.refundOf("bobs"), "and only that cost")

        // Moving a member's cost to another event does not include it there either.
        scenario.apply(CreateEvent("other", "other", DATE, participants = listOf(com.jacekgajek.kapelusz.model.Participant("bob"))), alice)
        scenario.apply(UpdateCost("bobs", incurredForEventId = Change("other")), bob)
        assertEquals(null, scenario.band.event("other")!!.refundOf("bobs"))
    }

    @Test
    fun creatingAnEventOrAddingSomeoneIncludesNothing() {
        val scenario = Scenario("Alice", "Bob").event("first", "Alice", "Bob")
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(AddCost("c", "bob", pln("40"), DATE), bob)
        scenario.event("gig", "Alice", "Bob")
        assertTrue(scenario.band.event("gig")!!.refunds.isEmpty())
        scenario.event("later", "Alice").apply(AddParticipant("later", "bob"))
        assertTrue(scenario.band.event("later")!!.refunds.isEmpty())
    }

    @Test
    fun artistsLookAfterTheirOwnCostsOnly() {
        val scenario = gig()
        val carol = scenario.userFor("carol", Role.ARTIST)
        scenario.apply(AddCost("own", "carol", pln("20"), DATE, incurredForEventId = "gig"), carol)
        scenario.apply(AddCost("general", "carol", pln("5"), DATE), carol)
        scenario.apply(UpdateCost("own", amount = Change(pln("25"))), carol)
        assertEquals(null, scenario.band.event("gig")!!.refundOf("own"), "waits for a lead")
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AddCost("x", "bob", pln("5"), DATE), carol).reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AddHolding("gig", "h", "carol", pln("100"), DOOR), carol).reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SettleSuggestions("gig", "carol"), carol).reason)
    }

    @Test
    fun artistsNeverHoldMoney() {
        val scenario = gig()
        scenario.userFor("carol", Role.ARTIST)
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(AddHolding("gig", "h", "carol", pln("100"), DOOR)).reason)
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(SetTreasurer("carol")).reason)

        // They are only paid: someone who collected pays them, and they cannot record it themselves.
        scenario.apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
        val carol = scenario.userFor("carol", Role.ARTIST)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RecordTransfer("gig", "t", "alice", "carol", pln("100")), carol).reason)
    }

    @Test
    fun someoneHoldingMoneyCannotBecomeAnArtist() {
        val scenario = gig().apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
        assertEquals(RejectionReason.HOLDS_MONEY, scenario.rejected(SetMemberRole("bob", Role.ARTIST)).reason)
        assertEquals(RejectionReason.HOLDS_MONEY, scenario.rejected(LinkUser("bob", "user-bob", Role.ARTIST)).reason)
        scenario.apply(SettleSuggestions("gig")).apply(LinkUser("bob", "user-bob", Role.ARTIST))
        assertEquals(Role.ARTIST, scenario.band.member("bob")!!.role, "settled events don't matter")

        scenario.apply(SetTreasurer("carol")).apply(LinkUser("carol", "user-carol", Role.MEMBER))
        assertEquals(RejectionReason.HOLDS_MONEY, scenario.rejected(SetMemberRole("carol", Role.ARTIST)).reason, "the band fund holder")
    }

    @Test
    fun onlyALeadsEditRaisesAnIncludedRefund() {
        val scenario = gig()
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"), bob).apply(SetRefund("gig", "c", pln("40")))
        scenario.apply(UpdateCost("c", amount = Change(pln("30"))), bob)
        assertEquals(pln("30"), scenario.band.event("gig")!!.refundOf("c")!!.amount, "lowering follows")
        scenario.apply(UpdateCost("c", amount = Change(pln("400"))), bob)
        val gig = scenario.band.event("gig")!!
        assertEquals(pln("30"), gig.refundOf("c")!!.amount, "the rest waits for a lead")
        assertEquals(listOf("c"), CostLedger.pendingFromEarlier(scenario.band, gig).map { it.id }, "in this event's Waiting list")
        assertEquals(pln("400"), CostLedger.maxRefundAt(scenario.band, scenario.band.cost("c")!!, gig), "what Refund here sets")
    }

    /** An artist who ends up owing (overpaid, or owing from before) is settled by a lead; they can't do it themselves. */
    @Test
    fun aLeadSettlesWhatAnArtistOwes() {
        val scenario = gig().apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
            .apply(AddCost("c", "carol", pln("50"), DATE)).apply(SetRefund("gig", "c", pln("50"))).apply(SettleSuggestions("gig"))
        val carol = scenario.userFor("carol", Role.ARTIST)
        val alice = scenario.userFor("alice", Role.OWNER)
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(RecordTransfer("gig", "over", "alice", "carol", pln("1")), alice).reason, "never more than owed")
        // The refund taken back after it was paid: she got too much (less her bigger share now).
        scenario.apply(SetRefund("gig", "c", pln("0")), alice)
        val owes = scenario.settle("gig").line("carol")!!.delta
        assertTrue(owes.isPositive, "overpaid: owes some back")
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RecordTransfer("gig", "back", "carol", "alice", owes), carol).reason)
        scenario.apply(RecordTransfer("gig", "back", "carol", "alice", owes), alice).apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun artistsLookAfterTheirOwnReceipts() {
        val scenario = gig()
        val carol = scenario.userFor("carol", Role.ARTIST)
        scenario.apply(AddCost("own", "carol", pln("20"), DATE), carol).apply(AddCost("bobs", "bob", pln("20"), DATE))
        val photo = Attachment("a", AttachmentKind.IMAGE, sizeBytes = 100)
        scenario.apply(AddAttachment("own", photo), carol).apply(RemoveAttachment("own", "a"), carol)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AddAttachment("bobs", photo), carol).reason)
    }
}
