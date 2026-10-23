package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.CONTRACT
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ITERATION_4 §2.2: a fee the organiser has not paid yet. */
class AwaitedFeeTest {

    /** Bob took 2000 at the door; the 3000 contract is awaited; three equal shares of 1666.67 (rounding aside). */
    private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
        .apply(AddHolding("gig", "door", "bob", pln("2000"), DOOR))
        .apply(AddHolding("gig", "fee", ORGANISER, pln("3000"), CONTRACT))

    @Test
    fun theFeeCountsInSharesButOnlyMoneyHeldMoves() {
        val scenario = gig()
        val open = scenario.settle("gig")
        assertEquals(pln("5000"), open.income)
        assertEquals(EventStatus.OPEN, open.status)
        // Bob passes on only his surplus over his share; nobody is told to pay the fee.
        assertTrue(open.suggestions.all { it.from == "bob" }, open.suggestions.toString())
        assertEquals(open.line("bob")!!.delta, open.suggestions.fold(pln("0")) { sum, it -> sum + it.amount })

        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.AWAITING, scenario.settle("gig").status)
        assertEquals(RejectionReason.EVENT_UNSETTLED, scenario.rejected(SetEventArchived("gig", true)).reason)

        scenario.apply(ReceiveAwaited("gig", "fee", "paid", "alice", pln("3000")))
        val paid = scenario.settle("gig")
        assertEquals(EventStatus.OPEN, paid.status)
        assertTrue(paid.suggestions.all { it.from == "alice" })
        scenario.apply(SettleSuggestions("gig")).apply(SetEventArchived("gig", true))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun partOfTheFeeCanComeAndTheContractFollowsTheLastPart() {
        val scenario = gig().apply(AddAttachment(AttachmentOwner.OfHolding("gig", "fee"), Attachment("contract", AttachmentKind.PDF, "contract.pdf", 1_000)))
        scenario.apply(ReceiveAwaited("gig", "fee", "first", "carol", pln("1000")))
        val event = scenario.band.event("gig")!!
        assertEquals(pln("2000"), event.awaited)
        assertEquals(listOf("contract"), event.holdings.single { it.id == "fee" }.attachments.map { it.id }, "still with what is awaited")
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(ReceiveAwaited("gig", "fee", "x", "carol", pln("2001"))).reason)
        scenario.apply(ReceiveAwaited("gig", "fee", "rest", "carol", pln("2000")))
        val after = scenario.band.event("gig")!!
        assertEquals(pln("0"), after.awaited)
        assertEquals(listOf("contract"), after.holdings.single { it.id == "rest" }.attachments.map { it.id })
        assertEquals(pln("5000"), scenario.settle("gig").income, "income unchanged by the arrival")
    }

    @Test
    fun paidToTheBandsAccount() {
        val scenario = gig().apply(SetTreasurer("alice")).apply(ReceiveAwaited("gig", "fee", "paid", BAND_FUND, pln("3000")))
        val settlement = scenario.settle("gig")
        assertTrue(settlement.lines.any { it.partyId == BAND_FUND })
        assertTrue(settlement.suggestions.any { it.from == BAND_FUND }, "paid out of the fund")
        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        assertEquals(pln("0"), BandFund.balance(scenario.band), "paid out in full")
        assertTrue(BandFund.isHandedOver(scenario.band.event("gig")!!))
    }

    @Test
    fun onlyLeadsRecordAndReceiveIt() {
        val scenario = gig()
        val bob = scenario.userFor("bob", Role.MEMBER)
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.userFor("carol", Role.ARTIST)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AddHolding("gig", "f2", ORGANISER, pln("1"), CONTRACT), bob).reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(ReceiveAwaited("gig", "fee", "x", "bob", pln("1")), bob).reason)
        assertEquals(RejectionReason.ARTIST_HOLDS_NO_MONEY, scenario.rejected(ReceiveAwaited("gig", "fee", "x", "carol", pln("1")), alice).reason)
        scenario.apply(AddHolding("gig", "f2", ORGANISER, pln("100"), CONTRACT), alice)
        assertEquals("alice", scenario.band.event("gig")!!.holdings.single { it.id == "f2" }.recordedBy, "the lead to remind")
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(ReceiveAwaited("gig", "door", "x", "alice", pln("1"))).reason, "not an awaited fee")
    }

    @Test
    fun receivingCanBeUndone() {
        val scenario = gig()
        val before = scenario.band
        val receive = ReceiveAwaited("gig", "fee", "paid", "alice", pln("3000"))
        scenario.apply(receive)
        scenario.apply(Undo("u", receive, BandChange.between(before, scenario.band)))
        assertEquals(pln("3000"), scenario.band.event("gig")!!.awaited)
    }

    @Test
    fun receivingIsChecked() {
        val scenario = gig()
        assertEquals(RejectionReason.NO_TREASURER, scenario.rejected(ReceiveAwaited("gig", "fee", "x", BAND_FUND, pln("1"))).reason, "fund off")
        assertEquals(RejectionReason.NOT_A_PARTICIPANT, scenario.rejected(ReceiveAwaited("gig", "fee", "x", "nobody", pln("1"))).reason)
        assertEquals(RejectionReason.DUPLICATE_ID, scenario.rejected(ReceiveAwaited("gig", "fee", "door", "alice", pln("1"))).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(ReceiveAwaited("gig", "fee", "x", "alice", pln("0"))).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(com.jacekgajek.kapelusz.ops.AddMember(ORGANISER, "Org")).reason, "a reserved id")
    }

    @Test
    fun aFeeHeldByTheFundKeepsItInUse() {
        val scenario = gig().apply(SetTreasurer("alice")).apply(ReceiveAwaited("gig", "fee", "paid", BAND_FUND, pln("3000")))
        assertEquals(pln("3000"), BandFund.balance(scenario.band))
        assertEquals(pln("-3000"), BandFund.notHandedOver(scenario.band), "to pay out")
        assertEquals(RejectionReason.FUND_NOT_EMPTY, scenario.rejected(com.jacekgajek.kapelusz.ops.DisableBandFund).reason)
    }
}
