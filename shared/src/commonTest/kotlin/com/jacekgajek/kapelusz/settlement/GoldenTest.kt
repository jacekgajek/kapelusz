package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.CONTRACT
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.IncomeSource.HAT
import com.jacekgajek.kapelusz.model.IncomeSource.MERCH
import com.jacekgajek.kapelusz.model.IncomeSource.SPONSORSHIP
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.settlement.CostStatus
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.SettlementProblem
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import com.jacekgajek.kapelusz.targets
import com.jacekgajek.kapelusz.view.BandView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Golden cases from ITERATION_1 §2.6, based on the (anonymized) spreadsheets. */
class GoldenTest {

    private fun firstBand() = Scenario("Alice", "Bob", "Carol", "Dave", "Eve", "Frank")
    private fun secondBand() = Scenario("Carol", "Grace", "Heidi", "Dave", "Ivan")

    /** G1: even split, two members' costs refunded, a roster member not taking part. */
    private fun g1() = firstBand()
        .event("oakfield", "Alice", "Bob", "Carol", "Dave", "Frank")
        .apply(AddCost("c-carol", "carol", pln("30"), DATE, incurredForEventId = "oakfield"))
        .apply(AddCost("c-dave", "dave", pln("322"), DATE, incurredForEventId = "oakfield"))
        .apply(AddHolding("oakfield", "h1", "dave", pln("1000"), DOOR))

    @Test
    fun g1EvenSplitWithRefunds() {
        val s = g1().settle("oakfield")
        assertEquals(pln("648"), s.net)
        assertEquals(
            mapOf("alice" to pln("129.60"), "bob" to pln("129.60"), "carol" to pln("159.60"), "dave" to pln("451.60"), "frank" to pln("129.60")),
            s.targets,
        )
        assertEquals(EventStatus.OPEN, s.status)
        assertEquals(
            listOf(
                SuggestedTransfer("dave", "carol", pln("159.60")),
                SuggestedTransfer("dave", "alice", pln("129.60")),
                SuggestedTransfer("dave", "bob", pln("129.60")),
                SuggestedTransfer("dave", "frank", pln("129.60")),
            ),
            s.suggestions,
        )
        val settled = g1().apply(SettleSuggestions("oakfield"))
        assertEquals(EventStatus.SETTLED, settled.settle("oakfield").status)
    }

    /** G2: guest at 0% who collected money, several holders, absent member. */
    private fun g2() = firstBand()
        .event("millbrook", "Alice", "Bob", "Carol", "Eve", guests = listOf("Helper 2" to 0))
        .apply(AddHolding("millbrook", "h1", "alice", pln("500"), DOOR))
        .apply(AddHolding("millbrook", "h2", "bob", pln("500"), DOOR))
        .apply(AddHolding("millbrook", "h3", "carol", pln("500"), DOOR))
        .apply(AddHolding("millbrook", "h4", "helper-2", pln("500"), DOOR))

    @Test
    fun g2GuestHolderWithZeroShare() {
        val s = g2().settle("millbrook")
        assertEquals(
            mapOf("alice" to pln("500"), "bob" to pln("500"), "carol" to pln("500"), "eve" to pln("500"), "helper-2" to pln("0")),
            s.targets,
        )
        assertEquals(listOf(SuggestedTransfer("helper-2", "eve", pln("500"))), s.suggestions)
    }

    /** G3: several income sources with notes, all consumed by one refund: settled without any transfer. */
    private fun g3() = secondBand()
        .event("carried-over", "Carol", "Grace", "Heidi", "Dave", "Ivan")
        .apply(AddHolding("carried-over", "h1", "carol", pln("1870"), CONTRACT))
        .apply(AddHolding("carried-over", "h2", "carol", pln("200"), SPONSORSHIP, "sponsorship via Grace"))

    @Test
    fun g3IncomeConsumedByRefund() {
        val scenario = g3().apply(AddCost("c1", "carol", pln("2070"), DATE, incurredForEventId = "carried-over"))
        val s = scenario.settle("carried-over")
        assertEquals(pln("0"), s.net)
        assertEquals(pln("2070"), s.line("carol")!!.target)
        assertTrue(s.lines.filter { it.partyId != "carol" }.all { it.target.isZero })
        assertEquals(EventStatus.SETTLED, s.status)
        assertTrue(s.suggestions.isEmpty())
    }

    /** G4: one cost repaid in parts across three events. */
    @Test
    fun g4AmortizationAcrossEvents() {
        val all = arrayOf("Carol", "Grace", "Heidi", "Dave", "Ivan")
        val scenario = g3()
            .apply(AddCost("gear", "carol", pln("4970"), DATE))
            .apply(SetRefund("carried-over", "gear", pln("2070")))
        fun cost() = scenario.band.cost("gear")!!
        assertEquals(CostStatus.PARTLY_REFUNDED, BandView(scenario.band).costStatuses.status(cost()))
        assertEquals(pln("2900"), CostLedger.outstanding(scenario.band, cost()))

        scenario.event("southgate", *all)
            .apply(AddHolding("southgate", "h1", "carol", pln("1000"), CONTRACT))
        assertEquals(listOf("gear"), CostLedger.pendingFromEarlier(scenario.band, scenario.band.event("southgate")!!).map { it.id })
        scenario.apply(SetRefund("southgate", "gear", pln("1000")))
        assertEquals(pln("1900"), CostLedger.outstanding(scenario.band, cost()))

        scenario.event("grand-hotel", *all)
            .apply(AddHolding("grand-hotel", "h1", "carol", pln("400"), MERCH))
            .apply(AddHolding("grand-hotel", "h2", "carol", pln("1500"), CONTRACT))
            .apply(SetRefund("grand-hotel", "gear", pln("1900")))
        assertEquals(CostStatus.REFUNDED, BandView(scenario.band).costStatuses.status(cost()))
        listOf("carried-over", "southgate", "grand-hotel").forEach {
            assertEquals(EventStatus.SETTLED, scenario.settle(it).status, it)
        }
    }

    /** G5: whole income to the band fund, collected by a non-treasurer. */
    private fun g5() = secondBand()
        .apply(SetTreasurer("carol"))
        .event("four-lanterns", "Carol", "Grace", "Heidi", "Dave", "Ivan")
        .apply(AddHolding("four-lanterns", "h1", "dave", pln("710"), HAT, "hat at Four Lanterns"))
        .apply(SetFundAmount("four-lanterns", pln("710")))

    @Test
    fun g5WholeIncomeToFund() {
        val s = g5().settle("four-lanterns")
        assertEquals(pln("0"), s.net)
        assertTrue(s.lines.filter { it.partyId != BAND_FUND }.all { it.target.isZero })
        assertEquals(listOf(SuggestedTransfer("dave", BAND_FUND, pln("710"))), s.suggestions)
    }

    /** G6: the treasurer collected the fund money themselves; fund totals across G5 and G6. */
    @Test
    fun g6TreasurerKeepsFundMoney() {
        val scenario = g5()
            .event("elmwood", "Carol", "Grace", "Dave", "Ivan")
            .apply(AddHolding("elmwood", "h1", "carol", pln("813"), CONTRACT))
            .apply(SetFundAmount("elmwood", pln("813")))
        assertEquals(listOf(SuggestedTransfer("carol", BAND_FUND, pln("813"))), scenario.settle("elmwood").suggestions)
        assertEquals(pln("1523"), BandFund.notHandedOver(scenario.band))
        assertEquals(pln("0"), BandFund.balance(scenario.band))

        scenario.apply(SettleSuggestions("four-lanterns")).apply(SettleSuggestions("elmwood"))
        assertEquals(pln("1523"), BandFund.balance(scenario.band))
        assertEquals(pln("0"), BandFund.notHandedOver(scenario.band))
        assertEquals(EventStatus.SETTLED, scenario.settle("elmwood").status)
    }

    /** G7: a single holder owes several members, one member sits out. */
    @Test
    fun g7SingleHolderOwesSeveral() {
        val scenario = secondBand()
            .event("riverside", "Carol", "Grace", "Dave", "Ivan")
            .apply(AddHolding("riverside", "h1", "carol", pln("1200"), CONTRACT))
        val s = scenario.settle("riverside")
        assertTrue(s.targets.values.all { it == pln("300") })
        assertEquals(
            // Equal amounts: in order of their ids, not the roster's.
            listOf("dave", "grace", "ivan").map { SuggestedTransfer("carol", it, pln("300")) },
            s.suggestions,
        )
        assertEquals(EventStatus.OPEN, s.status)
        assertEquals(EventStatus.SETTLED, scenario.apply(SettleSuggestions("riverside")).settle("riverside").status)
    }

    /** G8: leftover grosz goes to the first party by id (here also the roster's order). */
    @Test
    fun g8Rounding() {
        val s = Scenario("A", "B", "C").event("e", "A", "B", "C")
            .apply(AddHolding("e", "h", "a", pln("100"), DOOR))
            .settle("e")
        assertEquals(listOf(pln("33.34"), pln("33.33"), pln("33.33")), s.lines.map { it.target })
    }

    /** G9: unequal weights; the leftover grosz goes to the largest remainder (B and C tie, B's id is first). */
    @Test
    fun g9UnequalWeights() {
        val s = Scenario("A", "B", "C")
            .apply(CreateEvent("e", "e", DATE, participants = listOf(Participant("a", 150), Participant("b"), Participant("c"))))
            .apply(AddHolding("e", "h", "a", pln("1000"), DOOR))
            .settle("e")
        assertEquals(listOf(pln("428.57"), pln("285.72"), pln("285.71")), s.lines.map { it.target })
    }

    /** G10: a loss blocks settlement; deferring part of a refund fixes it. */
    @Test
    fun g10LossAndDeferral() {
        val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
            .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
            .apply(AddCost("c", "bob", pln("150"), DATE, incurredForEventId = "e"))
        val loss = scenario.settle("e")
        assertEquals(EventStatus.LOSS, loss.status)
        assertEquals(setOf(SettlementProblem.LOSS), loss.problems)
        assertFalse(loss.canSettle)
        assertEquals(RejectionCode.CONFLICT, scenario.rejected(SettleSuggestions("e")).code)

        scenario.apply(SetRefund("e", "c", pln("100")))
        val fixed = scenario.settle("e")
        assertEquals(pln("0"), fixed.net)
        assertTrue(fixed.canSettle)
        assertEquals(pln("50"), CostLedger.outstanding(scenario.band, scenario.band.cost("c")!!))
    }

    /** G11: manual hand-over between holders, then suggestions recompute. */
    @Test
    fun g11HandOverThenRecompute() {
        val scenario = g2().apply(RecordTransfer("millbrook", "t1", "carol", "alice", pln("500"), "consolidation"))
        val s = scenario.settle("millbrook")
        assertEquals(pln("500"), s.line("alice")!!.delta)
        assertEquals(pln("-500"), s.line("carol")!!.delta)
        assertEquals(2, s.suggestions.size)

        scenario.apply(SettleSuggestions("millbrook"))
        val settled = scenario.settle("millbrook")
        assertEquals(EventStatus.SETTLED, settled.status)
        assertEquals(settled.income.minor, settled.lines.sumOf { it.balance.minor })
    }

    /** G12: a late edit reopens a settled event with compensating suggestions only. */
    @Test
    fun g12LateEditReopens() {
        val scenario = g1().apply(SettleSuggestions("oakfield"))
        val recorded = scenario.band.event("oakfield")!!.transfers

        // Money has moved: the cost only remembers the gig (ITERATION_3 R2) and the gig stays settled ...
        scenario.apply(AddCost("c-bob", "bob", pln("50"), DATE, incurredForEventId = "oakfield"))
        assertEquals(EventStatus.SETTLED, scenario.settle("oakfield").status)
        // ... until a lead includes the refund, which reopens it.
        scenario.apply(SetRefund("oakfield", "c-bob", pln("50")))
        val s = scenario.settle("oakfield")
        assertEquals(EventStatus.OPEN, s.status)
        assertEquals(recorded, scenario.band.event("oakfield")!!.transfers)
        assertEquals(
            setOf("alice", "carol", "dave", "frank").map { SuggestedTransfer(it, "bob", pln("10")) }.toSet(),
            s.suggestions.toSet(),
        )
    }

    /** The members' stored order moves no grosz and no suggested transfer: ties go by party id. */
    @Test
    fun rosterOrderChangesNothing() {
        val scenario = Scenario("A", "B", "C", "D")
            .event("e", "A", "B", "C", "D")
            .apply(AddHolding("e", "h", "a", pln("100.01"), DOOR))
        val before = scenario.settle("e")
        assertEquals(listOf(pln("25.01"), pln("25"), pln("25"), pln("25")), before.lines.map { it.target })
        scenario.storeMembersInOrder("d", "b", "c", "a")
        val after = scenario.settle("e")
        assertEquals(before.lines.associate { it.partyId to it.target }, after.lines.associate { it.partyId to it.target })
        assertEquals(before.suggestions, after.suggestions)
    }
}
