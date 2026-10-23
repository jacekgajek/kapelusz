package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.domain.CostStatuses
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RefuseCost
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.UpdateCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ITERATION_4 §1.1 (artists) and §1.2 (costs wait for a lead: Include or Refuse). */
class ArtistAndRefuseTest {

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
    fun aRefusedCostWaitsNowhereUntilItsOwnerResubmitsIt() {
        val scenario = gig()
        val bob = scenario.userFor("bob", Role.MEMBER)
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.apply(SetTreasurer("alice"))
        scenario.apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"), bob)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RefuseCost("c"), bob).reason, "only leads refuse")
        scenario.apply(RefuseCost("c"), alice)

        val event = scenario.band.event("gig")!!
        assertTrue(CostLedger.pendingFromEarlier(scenario.band, event).isEmpty())
        assertEquals(CostStatus.REFUSED, CostLedger.status(scenario.band, scenario.band.cost("c")!!))
        assertEquals(Money.ZERO, CostStatuses(scenario.band).unpaid(scenario.band.cost("c")!!), "not expected back")
        assertEquals(RejectionReason.COST_REFUSED, scenario.rejected(SetRefund("gig", "c", pln("40")), alice).reason)
        assertEquals(RejectionReason.COST_REFUSED, scenario.rejected(AddFundEntry("f", DATE, "refund", pln("-40"), "c"), alice).reason)

        // A lead's edit keeps it refused; the owner's edit resubmits it.
        scenario.apply(UpdateCost("c", description = Change("Strings")), alice)
        assertTrue(scenario.band.cost("c")!!.refused)
        scenario.apply(UpdateCost("c"), bob)
        assertFalse(scenario.band.cost("c")!!.refused)
        assertEquals(listOf("c"), CostLedger.pendingFromEarlier(scenario.band, scenario.band.event("gig")!!).map { it.id })
    }

    @Test
    fun onlyACostWithNothingRefundedCanBeRefused() {
        val scenario = gig()
        scenario.apply(AddCost("c", "bob", pln("40"), DATE)).apply(SetRefund("gig", "c", pln("10")))
        assertEquals(RejectionReason.COST_PARTLY_REFUNDED, scenario.rejected(RefuseCost("c")).reason)
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
}
