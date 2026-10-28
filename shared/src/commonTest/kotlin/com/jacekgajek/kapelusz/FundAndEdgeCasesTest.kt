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
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
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
        scenario.apply(AddCost("van", "bob", pln("100"), DATE)).apply(AddFundEntry("f", DATE, "Van rental", pln("-100"), costId = "van"))
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

    /** ITERATION_3 R10: with the fund off, nothing may leave it holding or owing money. */
    @Test
    fun aDisabledFundCannotBeChangedBackIntoUse() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
            .apply(SetFundAmount("e", pln("100")))
            .apply(SettleSuggestions("e"))
            .apply(AddCost("van", "bob", pln("100"), DATE))
            .apply(AddFundEntry("f", DATE, "Van rental", pln("-100"), costId = "van"))
            .apply(DisableBandFund)
        val toFund = scenario.band.event("e")!!.transfers.first { it.to == BAND_FUND }

        assertEquals(RejectionReason.NO_TREASURER, scenario.rejected(UpdateFundEntry("f", amount = Change(pln("-50")))).reason)
        assertEquals(RejectionReason.NO_TREASURER, scenario.rejected(RemoveFundEntry("f")).reason)
        assertEquals(RejectionReason.NO_TREASURER, scenario.rejected(RemoveTransfer("e", toFund.id)).reason)
        assertEquals(RejectionReason.NO_TREASURER, scenario.rejected(SetFundAmount("e", pln("50"))).reason)
        scenario.apply(UpdateFundEntry("f", description = Change("Van")))
        assertEquals("Van", scenario.band.fundEntries.single().description, "changes that keep it empty are fine")
    }

    /** ITERATION_3 R10: an event the fund pays for, with nothing collected, can be settled. */
    @Test
    fun anEventPaidOnlyByTheFundIsNotADraft() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
            .apply(AddCost("c", "bob", pln("80"), DATE, incurredForEventId = "e"))
        assertEquals(EventStatus.DRAFT, scenario.settle("e").status, "a member's cost alone does not open the event")

        scenario.apply(SetFundAmount("e", pln("-80")))
        val s = scenario.settle("e")
        assertEquals(EventStatus.OPEN, s.status)
        assertEquals(listOf(SuggestedTransfer(BAND_FUND, "bob", pln("80"))), s.suggestions)
        scenario.apply(SettleSuggestions("e"))
        assertEquals(EventStatus.SETTLED, scenario.settle("e").status)
    }

    /** ITERATION_3 R10: archived events are read-only, except restoring and deleting them. */
    @Test
    fun archivedEventsAreReadOnly() {
        val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
            .apply(SettleSuggestions("e"))
            .apply(SetEventArchived("e", true))
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(AddHolding("e", "h2", "bob", pln("1"), DOOR)).reason)
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(SettleSuggestions("e")).reason)
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(AddCost("c", "bob", pln("10"), DATE, incurredForEventId = "e")).reason)

        // Money has moved in it, so the cost's refund is set by hand.
        scenario.apply(SetEventArchived("e", false)).apply(AddCost("c", "bob", pln("10"), DATE, incurredForEventId = "e")).apply(SetRefund("e", "c", pln("10")))
        assertEquals(RejectionReason.EVENT_UNSETTLED, scenario.rejected(SetEventArchived("e", true)).reason, "the refund reopened it")
        scenario.apply(SettleSuggestions("e")).apply(SetEventArchived("e", true))
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(DeleteCost("c")).reason, "its refund is part of the archived event")
        scenario.event("d", "Alice").apply(SetEventArchived("d", true)).apply(DeleteEvent("d"))
        assertEquals(null, scenario.band.event("d"))
    }

    /** Only events with nothing left to settle can be archived: settled ones and drafts. */
    @Test
    fun unsettledEventsCannotBeArchived() {
        val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
        assertEquals(EventStatus.OPEN, scenario.settle("e").status)
        assertEquals(RejectionReason.EVENT_UNSETTLED, scenario.rejected(SetEventArchived("e", true)).reason)

        scenario.apply(SetFixedShare("e", "alice", pln("400")))
        assertEquals(EventStatus.LOSS, scenario.settle("e").status)
        assertEquals(RejectionReason.EVENT_UNSETTLED, scenario.rejected(SetEventArchived("e", true)).reason)

        scenario.apply(SetFixedShare("e", "alice", null, weight = 1)).apply(SettleSuggestions("e")).apply(SetEventArchived("e", true))
        assertEquals(true, scenario.band.event("e")!!.archived)

        scenario.event("draft", "Alice").apply(SetEventArchived("draft", true))
        assertEquals(true, scenario.band.event("draft")!!.archived, "a draft has no money in it")

        // A draft with a refund in it would count the cost as covered while nobody pays it.
        scenario.event("d2", "Bob").apply(AddCost("c2", "bob", pln("10"), DATE, incurredForEventId = "d2"))
        assertEquals(EventStatus.DRAFT, scenario.settle("d2").status)
        assertEquals(RejectionReason.EVENT_UNSETTLED, scenario.rejected(SetEventArchived("d2", true)).reason)
    }

    /** Money comes in from someone (a member, or outside the band) and leaves only to refund a cost (ITERATION_7 §2e). */
    @Test
    fun fundEntriesHaveAPayerOrACost() {
        val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice"))
        scenario.apply(AddFundEntry("fee", DATE, "Membership fee", pln("50"), paidBy = "bob"))
        scenario.apply(AddFundEntry("grant", DATE, "Grant", pln("500")))
        assertEquals(listOf("bob", null), scenario.band.fundEntries.map { it.paidBy })
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(AddFundEntry("x", DATE, "Strings", pln("-40"))).reason, "out only for a cost")
        assertEquals(RejectionReason.NOT_FOUND, scenario.rejected(AddFundEntry("x", DATE, "Fee", pln("5"), paidBy = "nobody")).reason)
        scenario.apply(AddCost("strings", "bob", pln("40"), DATE))
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(AddFundEntry("x", DATE, "Strings", pln("-40"), costId = "strings", paidBy = "bob")).reason)
        scenario.apply(AddFundEntry("r", DATE, "Strings", pln("-40"), costId = "strings"))

        // The payer can change (to outside the band too), the direction can't.
        scenario.apply(UpdateFundEntry("fee", paidBy = Change("alice")))
        scenario.apply(UpdateFundEntry("grant", paidBy = Change(null)))
        assertEquals("alice", scenario.band.fundEntries.first { it.id == "fee" }.paidBy)
        scenario.apply(UpdateFundEntry("fee", paidBy = Change(null)))
        assertEquals(null, scenario.band.fundEntries.first { it.id == "fee" }.paidBy)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(UpdateFundEntry("fee", amount = Change(pln("-50")))).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(UpdateFundEntry("r", amount = Change(pln("40")))).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(UpdateFundEntry("r", paidBy = Change("bob"))).reason, "a refund's cost names whose")
        assertEquals(RejectionReason.NOT_FOUND, scenario.rejected(UpdateFundEntry("fee", paidBy = Change("nobody"))).reason)
    }
}
