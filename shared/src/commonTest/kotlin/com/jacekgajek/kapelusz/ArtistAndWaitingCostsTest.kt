package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.RecordTransfer
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

    /** The bug from 2026-10-27: paid, then a refund added made the artist's share smaller; they hand the difference back. */
    @Test
    fun anArtistPaidBeforeALaterRefundHandsTheDifferenceBack() {
        val scenario = gig()
        val alice = scenario.userFor("alice", Role.OWNER)
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.userFor("carol", Role.ARTIST)
        scenario.apply(AddHolding("gig", "hat", "alice", pln("300"), DOOR), alice)
        // Everyone settled but Bob: Carol got her 100.
        scenario.apply(RecordTransfer("gig", "to-carol", "alice", "carol", pln("100")), alice)
        // Bob's cost, accepted for this gig: every share drops to 90, so Carol was paid 10 too much.
        scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
        scenario.apply(SetRefund("gig", "bobs", pln("30")), alice)

        scenario.apply(SettleSuggestions("gig"), alice)
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        val back = scenario.band.event("gig")!!.transfers.filter { it.from == "carol" }
        assertEquals(listOf(pln("10")), back.map { it.amount }, "Carol hands back what she was paid too much")
    }

    @Test
    fun anArtistSeesOnlyTheirOwnTransfers() {
        val scenario = gig()
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.userFor("carol", Role.ARTIST)
        scenario.apply(AddHolding("gig", "hat", "alice", pln("600"), DOOR), alice)
        val owed = Artists.ownTransfers(scenario.band, scenario.settle("gig"), "carol")
        assertEquals(listOf(Triple("alice", "carol", pln("200"))), owed.map { Triple(it.from, it.to, it.amount) }, "not Alice → Bob")

        // Paid, then Bob's refund makes her share smaller: she sees whom she hands back to.
        scenario.apply(RecordTransfer("gig", "to-carol", "alice", "carol", pln("200")), alice)
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
        scenario.apply(SetRefund("gig", "bobs", pln("30")), alice)
        val back = Artists.ownTransfers(scenario.band, scenario.settle("gig"), "carol")
        assertEquals(listOf(Triple("carol", "bob", pln("10"))), back.map { Triple(it.from, it.to, it.amount) }, "to Bob, who is owed")
    }

    @Test
    fun artistsMarkTheirOwnTransfersPaidOrReceived() {
        val scenario = Scenario("Lead", "Alice", "Bob", "Dane").event("gig", "Alice", "Bob", "Dane")
        val alice = scenario.userFor("alice", Role.MEMBER)
        val bob = scenario.userFor("bob", Role.ARTIST)
        val dane = scenario.userFor("dane", Role.ARTIST)
        scenario.apply(AddHolding("gig", "hat", "alice", pln("300"), DOOR), alice)
        scenario.apply(SettleSuggestions("gig", "alice"), alice)
        // Bob's cost, accepted: Alice owes him his refund's share and Dane hands back what he got too much.
        scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
        scenario.apply(SetRefund("gig", "bobs", pln("30")))
        val owed = scenario.settle("gig").suggestions.map { Triple(it.from, it.to, it.amount) }
        assertEquals(listOf(Triple("alice", "bob", pln("10")), Triple("dane", "bob", pln("10"))), owed.sortedBy { it.first })

        // Each artist in it marks it, without a lead; nobody else's.
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RecordTransfer("gig", "x", "alice", "bob", pln("10")), dane).reason)
        scenario.apply(RecordTransfer("gig", "t1", "dane", "bob", pln("10")), dane)
        scenario.apply(RecordTransfer("gig", "t2", "alice", "bob", pln("10")), bob)
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun anArtistHandsBackNoMoreThanTheyOwe() {
        val scenario = gig()
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.userFor("carol", Role.ARTIST)
        scenario.apply(AddHolding("gig", "hat", "alice", pln("300"), DOOR), alice)
        scenario.apply(RecordTransfer("gig", "to-carol", "alice", "carol", pln("100")), alice)
        // Settled with Carol: she owes nothing, so passing money on is still refused.
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(RecordTransfer("gig", "from-carol", "carol", "bob", pln("10")), alice).reason)
        // A refund makes her owe 10: no more than that, and exactly that.
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
        scenario.apply(SetRefund("gig", "bobs", pln("30")), alice)
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(RecordTransfer("gig", "back-11", "carol", "bob", pln("11")), alice).reason)
        scenario.apply(RecordTransfer("gig", "back-10", "carol", "bob", pln("10")), alice)
    }

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

        // They are only paid: someone who collected pays them, and either of the two records it (ITERATION_7 §3.2).
        scenario.apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
        val carol = scenario.userFor("carol", Role.ARTIST)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RecordTransfer("gig", "t", "alice", "bob", pln("100")), carol).reason, "not someone else's")
        scenario.apply(RecordTransfer("gig", "t", "alice", "carol", pln("100")), carol)
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

    /** An artist who ends up owing (overpaid, or owing from before) hands it back and records it (ITERATION_7 §3.2). */
    @Test
    fun anArtistRecordsWhatTheyHandBack() {
        val scenario = gig().apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
            .apply(AddCost("c", "carol", pln("50"), DATE)).apply(SetRefund("gig", "c", pln("50"))).apply(SettleSuggestions("gig"))
        val carol = scenario.userFor("carol", Role.ARTIST)
        val alice = scenario.userFor("alice", Role.OWNER)
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(RecordTransfer("gig", "over", "alice", "carol", pln("1")), alice).reason, "never more than owed")
        // The refund taken back after it was paid: she got too much (less her bigger share now).
        scenario.apply(SetRefund("gig", "c", pln("0")), alice)
        val owes = scenario.settle("gig").line("carol")!!.delta
        assertTrue(owes.isPositive, "overpaid: owes some back")
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(RecordTransfer("gig", "more", "carol", "alice", owes + pln("1")), carol).reason)
        scenario.apply(RecordTransfer("gig", "back", "carol", "alice", owes), carol).apply(SettleSuggestions("gig"))
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
