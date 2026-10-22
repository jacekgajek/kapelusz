package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SettlementProblem
import com.jacekgajek.kapelusz.domain.SuggestedTransfer
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FundAndEdgeCasesTest {

    /** Third way out of a loss: the band fund covers the shortfall and pays out through the treasurer. */
    @Test
    fun negativeFundAmountCoversLoss() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
            .apply(AddCost("c", "bob", pln("150"), DATE, incurredForEventId = "e"))
        assertEquals(EventStatus.LOSS, scenario.settle("e").status)

        scenario.apply(SetFundAmount("e", pln("-50")))
        val s = scenario.settle("e")
        assertEquals(pln("0"), s.net)
        assertEquals(pln("-50"), s.line(BAND_FUND)!!.target)
        assertEquals(
            listOf(SuggestedTransfer("alice", "bob", pln("100")), SuggestedTransfer(BAND_FUND, "bob", pln("50"))),
            s.suggestions,
        )
        scenario.apply(SettleSuggestions("e"))
        assertEquals(EventStatus.SETTLED, scenario.settle("e").status)
        assertEquals(pln("-50"), BandFund.balance(scenario.band), "the fund may go negative")
    }

    @Test
    fun noSharesBlocksSettlementUntilWeightsChange() {
        val scenario = Scenario("Alice", "Bob")
            .apply(CreateEvent("e", "e", DATE, participants = listOf(Participant("alice", 0), Participant("bob", 0))))
            .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
        val blocked = scenario.settle("e")
        assertEquals(setOf(SettlementProblem.NO_SHARES), blocked.problems)
        assertFalse(blocked.canSettle)
        assertTrue(blocked.suggestions.isEmpty())

        scenario.apply(SetWeight("e", "bob", 100))
        assertEquals(listOf(SuggestedTransfer("alice", "bob", pln("100"))), scenario.settle("e").suggestions)
    }

    @Test
    fun costRefundedFromBandFund() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice"))
            .apply(AddCost("c", "bob", pln("322"), DATE))
            .apply(AddFundEntry("f", DATE, "Refund Bob's train", pln("-300"), costId = "c"))
        val cost = { scenario.band.cost("c")!! }
        assertEquals(CostStatus.PARTLY_REFUNDED, CostLedger.status(scenario.band, cost()))
        assertEquals(pln("22"), CostLedger.outstanding(scenario.band, cost()))
        assertEquals(pln("-300"), BandFund.balance(scenario.band))

        assertEquals(RejectionCode.INVALID, scenario.rejected(UpdateFundEntry("f", amount = Change(pln("-323")))).code)
        assertEquals(RejectionCode.INVALID, scenario.rejected(UpdateFundEntry("f", amount = Change(pln("10")))).code)
        scenario.apply(UpdateFundEntry("f", amount = Change(pln("-322"))))
        assertEquals(CostStatus.REFUNDED, CostLedger.status(scenario.band, cost()))

        assertEquals(RejectionCode.INVALID, scenario.rejected(AddFundEntry("g", DATE, "again", pln("-1"), costId = "c")).code)
    }

    /** A refund assigned in an event counts as paid only once that event is settled. */
    @Test
    fun refundIsPlannedUntilEventIsSettled() {
        val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
            .apply(AddCost("c", "bob", pln("50"), DATE, incurredForEventId = "e"))
        val cost = { scenario.band.cost("c")!! }
        assertEquals(CostStatus.REFUND_PLANNED, CostLedger.status(scenario.band, cost()))
        assertEquals(pln("50"), CostLedger.unpaid(scenario.band, cost()))
        assertEquals(pln("0"), CostLedger.outstanding(scenario.band, cost()), "already assigned, cannot be assigned again")

        scenario.apply(SettleSuggestions("e"))
        assertEquals(CostStatus.REFUNDED, CostLedger.status(scenario.band, cost()))
        assertEquals(pln("0"), CostLedger.unpaid(scenario.band, cost()))

        scenario.apply(AddHolding("e", "h2", "alice", pln("10"), DOOR))
        assertEquals(CostStatus.REFUND_PLANNED, CostLedger.status(scenario.band, cost()), "reopened event: not final any more")
    }

    /** Paying part of a suggested transfer leaves the rest suggested. */
    @Test
    fun partialSettlementLeavesRemainder() {
        val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "alice", pln("300"), DOOR))
            .apply(RecordTransfer("e", "t1", "alice", "bob", pln("100")))
        assertEquals(listOf(SuggestedTransfer("alice", "bob", pln("50"))), scenario.settle("e").suggestions)
        assertEquals(EventStatus.OPEN, scenario.settle("e").status)

        scenario.apply(RecordTransfer("e", "t2", "alice", "bob", pln("50")))
        assertEquals(EventStatus.SETTLED, scenario.settle("e").status)
    }

    @Test
    fun theFundCanBeTurnedOffOnlyWhenItIsEmptyAndNothingIsOwed() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
            .apply(SetFundAmount("e", pln("100")))
        assertEquals(RejectionReason.FUND_NOT_EMPTY, scenario.rejected(DisableBandFund).reason, "the event still owes the fund 100")

        scenario.apply(SettleSuggestions("e"))
        assertEquals(pln("100"), BandFund.balance(scenario.band))
        assertEquals(RejectionReason.FUND_NOT_EMPTY, scenario.rejected(DisableBandFund).reason, "the fund holds 100")

        // Spent on the band: empty again, though it had transfers in the past.
        scenario.apply(AddFundEntry("f", DATE, "Van rental", pln("-100")))
        scenario.apply(DisableBandFund)
        assertEquals(null, scenario.band.treasurerMemberId)
        assertEquals(EventStatus.SETTLED, scenario.settle("e").status, "a settled event stays settled without the fund")
        assertTrue(scenario.settle("e").problems.isEmpty())

        scenario.apply(SetTreasurer("bob"))
        assertEquals("bob", scenario.band.treasurerMemberId, "choosing a holder turns it on again")
    }

    @Test
    fun owedAmountsOfDifferentEventsDoNotCancelOut() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice"))
            .event("a", "Alice", "Bob").apply(AddHolding("a", "h1", "bob", pln("300"), DOOR)).apply(SetFundAmount("a", pln("100")))
            .event("b", "Alice", "Bob").apply(AddHolding("b", "h2", "bob", pln("300"), DOOR)).apply(SetFundAmount("b", pln("-100")))
        assertEquals(pln("0"), BandFund.notHandedOver(scenario.band))
        assertEquals(RejectionReason.FUND_NOT_EMPTY, scenario.rejected(DisableBandFund).reason)
    }
}
