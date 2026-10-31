package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.MemberShares
import com.jacekgajek.kapelusz.settlement.SettlementProblem
import com.jacekgajek.kapelusz.targets
import com.jacekgajek.kapelusz.view.BandView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A participant's share as a percentage of the profit. */
class SharePercentTest {

    private fun gig(income: String = "1000") = Scenario("Alice", "Bob", "Carol").apply(SetTreasurer("alice"))
        .event("gig", "Alice", "Bob", "Carol")
        .apply(AddHolding("gig", "h", "bob", pln(income), DOOR))

    @Test
    fun aPercentageReplacesTheShareAndTheRestIsSplitByWeight() {
        val s = gig().apply(SetFixedShare("gig", "alice", null, percent = 50)).settle("gig")
        assertEquals(mapOf("alice" to pln("500"), "bob" to pln("250"), "carol" to pln("250")), s.targets)
        assertEquals(pln("500"), s.percentTotal)
        assertEquals(50, s.line("alice")!!.percent)
        assertTrue(s.problems.isEmpty())
    }

    @Test
    fun itIsOfTheSameProfitAsTheFundsPercentage() {
        val s = gig().apply(SetFundAmount("gig", Money.ZERO, percent = 10)).apply(SetFixedShare("gig", "alice", null, percent = 50)).settle("gig")
        // 10% + 50% of 1000: the fund 100, Alice 500, and 400 for Bob and Carol.
        assertEquals(pln("100"), s.fundAmount)
        assertEquals(mapOf("alice" to pln("500"), "bob" to pln("200"), "carol" to pln("200"), "band-fund" to pln("100")), s.targets)
    }

    @Test
    fun itIsAfterRefundsAndIncludesWhatTheFundAdds() {
        val scenario = gig("600")
            .apply(AddCost("c", "carol", pln("200"), DATE, incurredForEventId = "gig"))
            .apply(SetRefund("gig", "c", pln("200")))
            .apply(SetFixedShare("gig", "alice", null, percent = 50))
        assertEquals(pln("200"), scenario.settle("gig").line("alice")!!.share, "50% of 600 − 200")
        // The fund covers the refund: as if there were no cost at all.
        scenario.apply(SetFundAmount("gig", pln("-200")))
        assertEquals(pln("300"), scenario.settle("gig").line("alice")!!.share)
    }

    @Test
    fun itIsRoundedDownAndTheGroszeLeftGoToTheShares() {
        val s = gig("1000.01").apply(SetFixedShare("gig", "alice", null, percent = 50)).settle("gig")
        assertEquals(pln("500"), s.line("alice")!!.share)
        assertEquals(pln("500.01"), s.line("bob")!!.share + s.line("carol")!!.share)
    }

    @Test
    fun withEveryoneOnPercentagesAddingUpTo100TheGroszeLeftGoToThem() {
        val scenario = gig("1000.01")
            .apply(SetFixedShare("gig", "alice", null, percent = 50))
            .apply(SetFixedShare("gig", "bob", null, percent = 30))
            .apply(SetFixedShare("gig", "carol", null, percent = 20))
        val s = scenario.settle("gig")
        assertTrue(s.problems.isEmpty())
        assertEquals(mapOf("alice" to pln("500.01"), "bob" to pln("300"), "carol" to pln("200")), s.targets)
        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun moneyNobodyIsToGetIsAProblem() {
        val s = gig()
            .apply(SetFixedShare("gig", "alice", null, percent = 30))
            .apply(SetFixedShare("gig", "bob", pln("100")))
            .apply(SetFixedShare("gig", "carol", null, percent = 20))
            .settle("gig")
        assertEquals(setOf(SettlementProblem.NO_SHARES), s.problems)
    }

    @Test
    fun over100PercentIsAProblemNotALoss() {
        val scenario = gig().apply(SetFundAmount("gig", Money.ZERO, percent = 10))
            .apply(SetFixedShare("gig", "alice", null, percent = 50))
            .apply(SetFixedShare("gig", "bob", null, percent = 45))
        val s = scenario.settle("gig")
        assertEquals(setOf(SettlementProblem.OVER_PERCENT), s.problems)
        assertEquals(EventStatus.OPEN, s.status)
        assertTrue(s.suggestions.isEmpty())
    }

    @Test
    fun over100PercentDoesNotHideARealLoss() {
        val s = gig("1000")
            .apply(AddCost("c", "carol", pln("1200"), DATE, incurredForEventId = "gig"))
            .apply(SetRefund("gig", "c", pln("1200")))
            .apply(SetFundAmount("gig", Money.ZERO, percent = 10))
            .apply(SetFixedShare("gig", "alice", null, percent = 95))
            .settle("gig")
        assertEquals(setOf(SettlementProblem.OVER_PERCENT, SettlementProblem.LOSS), s.problems)
        assertEquals(EventStatus.LOSS, s.status)
        assertEquals(pln("200"), s.shortfall)
    }

    @Test
    fun aLossGivesAPercentageNothing() {
        val s = gig("100")
            .apply(AddCost("c", "carol", pln("300"), DATE, incurredForEventId = "gig"))
            .apply(SetRefund("gig", "c", pln("300")))
            .apply(SetFixedShare("gig", "alice", null, percent = 50))
            .settle("gig")
        assertEquals(Money.ZERO, s.line("alice")!!.share)
        assertEquals(setOf(SettlementProblem.LOSS), s.problems)
        assertEquals(pln("200"), s.shortfall)
    }

    @Test
    fun theWeightUnderneathComesBackAndZeroPercentIsNoShare() {
        val scenario = gig().apply(SetWeight("gig", "alice", 150)).apply(SetFixedShare("gig", "alice", null, percent = 40))
        scenario.band.event("gig")!!.participant("alice")!!.let {
            assertEquals(40, it.percent)
            assertEquals(150, it.weight)
        }
        scenario.apply(SetFixedShare("gig", "alice", pln("100")))
        scenario.band.event("gig")!!.participant("alice")!!.let {
            assertNull(it.percent, "a fixed amount replaces the percentage")
            assertEquals(pln("100"), it.fixedShare)
        }
        scenario.apply(SetFixedShare("gig", "alice", null))
        assertTrue(scenario.band.event("gig")!!.participant("alice")!!.let { it.byWeight && it.weight == 150 })
        scenario.apply(SetFixedShare("gig", "alice", null, percent = 0))
        assertTrue(scenario.band.event("gig")!!.participant("alice")!!.let { it.byWeight && it.weight == 0 })
    }

    @Test
    fun itIsChecked() {
        val scenario = gig()
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetFixedShare("gig", "alice", null, percent = 101)).reason)
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(AddGuest("gig", "dave", "Dave", percent = -5)).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(SetFixedShare("gig", "alice", pln("100"), percent = 10)).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(SetMemberShare("bob", 100, pln("100"), percent = 10)).reason)
        scenario.apply(AddGuest("gig", "dave", "Dave", percent = 20))
        assertEquals(20, scenario.band.event("gig")!!.participant("dave")!!.percent)
    }

    @Test
    fun aMembersDefaultCanBeAPercentage() {
        val scenario = Scenario("Alice", "Bob").event("a", "Alice", "Bob").event("b", "Alice")
        scenario.apply(SetMemberShare("bob", 100, null, events = listOf("a"), percent = 20))
        assertEquals(20, scenario.band.member("bob")!!.sharePercent)
        assertEquals(20, scenario.band.event("a")!!.participant("bob")!!.percent)
        assertEquals(listOf("a"), MemberShares.stillDefault(BandView(scenario.band), scenario.band.member("bob")!!).map { it.id })
        scenario.apply(AddParticipant("b", "bob", 100, percent = 20))
        assertEquals(20, scenario.band.member("bob")!!.asParticipant().percent)

        // Back to a weight: the events chosen go back to it too.
        val before = scenario.band
        val op: Operation = SetMemberShare("bob", 75, null, events = listOf("a", "b"))
        scenario.apply(op)
        assertNull(scenario.band.member("bob")!!.sharePercent)
        assertTrue(scenario.band.event("b")!!.participant("bob")!!.let { it.byWeight && it.weight == 75 })
        scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)))
        assertEquals(20, scenario.band.member("bob")!!.sharePercent)
        assertEquals(20, scenario.band.event("b")!!.participant("bob")!!.percent)
    }
}
