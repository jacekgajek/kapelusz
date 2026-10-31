package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetFundDefault
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UpdateHolding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The band fund as a percentage of an event's profit, and the band's default for new events (ITERATION_7 §5.2). */
class FundPercentTest {

    private fun band() = Scenario("Alice", "Bob", "Carol").apply(SetTreasurer("alice"))

    private fun gig(scenario: Scenario = band(), id: String = "gig") = scenario.event(id, "Alice", "Bob")

    @Test
    fun aPercentageIsOfTheProfitAfterRefundsRoundedDownToWholeUnits() {
        val scenario = gig()
            .apply(AddHolding("gig", "h", "alice", pln("4600.00"), DOOR))
            .apply(AddCost("c", "bob", pln("32.11"), DATE, incurredForEventId = "gig"))
            .apply(SetRefund("gig", "c", pln("32.11")))
            .apply(SetFundAmount("gig", Money.ZERO, percent = 10))
        // 10% of 4567.89 is 456.789: the fund gets 456, the players share the rest.
        val s = scenario.settle("gig")
        assertEquals(pln("456"), s.fundAmount)
        assertEquals(pln("456"), s.line(BAND_FUND)!!.target)
        assertEquals(pln("4111.89"), s.net)
        assertEquals(pln("2055.95") + pln("2055.94"), s.line("alice")!!.share + s.line("bob")!!.share)
    }

    @Test
    fun thePercentageFollowsTheIncomeAndTakesNothingFromALoss() {
        val scenario = gig().apply(SetFundAmount("gig", Money.ZERO, percent = 20))
        assertEquals(Money.ZERO, scenario.settle("gig").fundAmount, "no income yet")
        assertEquals(EventStatus.DRAFT, scenario.settle("gig").status)
        scenario.apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
        assertEquals(pln("200"), scenario.settle("gig").fundAmount)
        scenario.apply(AddCost("c", "bob", pln("1500"), DATE, incurredForEventId = "gig")).apply(SetRefund("gig", "c", pln("1500")))
        assertEquals(Money.ZERO, scenario.settle("gig").fundAmount, "a loss gives the fund nothing")
    }

    @Test
    fun aPercentEventSettlesWithTheFundAndAnAmountReplacesThePercentage() {
        val scenario = gig().apply(AddHolding("gig", "h", "bob", pln("1000"), DOOR)).apply(SetFundAmount("gig", Money.ZERO, percent = 10))
        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        assertEquals(pln("100"), BandFund.balance(scenario.band))

        scenario.apply(SetFundAmount("gig", pln("-30")))
        val event = scenario.band.event("gig")!!
        assertNull(event.fundPercent)
        assertEquals(pln("-30"), event.fund)
    }

    @Test
    fun zeroPercentIsNothingAndPercentagesAreChecked() {
        val scenario = gig().apply(SetFundAmount("gig", pln("50"), percent = 0))
        val event = scenario.band.event("gig")!!
        assertNull(event.fundPercent)
        assertEquals(Money.ZERO, event.fundAmount, "the amount is ignored with a percentage")
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetFundAmount("gig", Money.ZERO, percent = 101)).reason)
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetFundAmount("gig", Money.ZERO, percent = -5)).reason)
        val noFund = Scenario("Alice").event("e", "Alice")
        assertEquals(RejectionReason.NO_TREASURER, noFund.rejected(SetFundAmount("e", Money.ZERO, percent = 10)).reason)
    }

    @Test
    fun newEventsGetTheDefaultTheyAreCreatedWith() {
        val scenario = band().apply(SetFundDefault(FundCut(percent = 15)))
        assertEquals(FundCut(percent = 15), scenario.band.fundDefault)
        scenario.apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice")), fund = scenario.band.fundDefault))
        assertEquals(15, scenario.band.event("e")!!.fundPercent)
        scenario.apply(CreateEvent("f", "Gig", DATE, participants = listOf(Participant("alice")), fund = FundCut(amount = pln("300"))))
        assertEquals(pln("300"), scenario.band.event("f")!!.fundAmount)
        assertEquals(
            RejectionReason.INVALID_INPUT,
            scenario.rejected(CreateEvent("g", "Gig", DATE, fund = FundCut(amount = pln("300"), percent = 10))).reason,
        )
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetFundDefault(FundCut(amount = pln("-1")))).reason)
        assertEquals(RejectionReason.NO_TREASURER, Scenario("Alice").rejected(SetFundDefault(FundCut(percent = 10))).reason)
    }

    @Test
    fun aChangedDefaultGoesToTheEventsChosenThatCanStillTakeIt() {
        val scenario = band()
        gig(scenario, "open")
        gig(scenario, "done").apply(AddHolding("done", "h", "alice", pln("100"), DOOR)).apply(SettleSuggestions("done"))
        gig(scenario, "other").apply(SetFundAmount("other", pln("40")))
        assertEquals(listOf("open"), BandFund.stillDefault(scenario.band).map { it.id }, "settled and changed events are not offered")

        scenario.apply(SetFundDefault(FundCut(percent = 10), events = listOf("open", "done", "gone")))
        assertEquals(10, scenario.band.event("open")!!.fundPercent)
        assertNull(scenario.band.event("done")!!.fundPercent, "settled since it was chosen: left alone")
        assertEquals(listOf("open"), BandFund.stillDefault(scenario.band).map { it.id })

        scenario.apply(SetFundDefault(FundCut(percent = 0)))
        assertNull(scenario.band.fundDefault, "0% is no default")
    }

    @Test
    fun onlyLeadersSetTheDefault() {
        val scenario = band()
        val bob = scenario.userFor("bob", Role.MEMBER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetFundDefault(FundCut(percent = 10)), bob).reason)
        scenario.apply(SetFundDefault(FundCut(percent = 10)), scenario.userFor("carol", Role.LEAD))
    }

    @Test
    fun undoBringsBackTheOldDefaultAndEvents() {
        val scenario = band()
        gig(scenario)
        val before = scenario.band
        val op: Operation = SetFundDefault(FundCut(percent = 10), events = listOf("gig"))
        scenario.apply(op)
        scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)))
        assertNull(scenario.band.fundDefault)
        assertNull(scenario.band.event("gig")!!.fundPercent)
    }

    @Test
    fun turningTheFundOffDropsItsDefaultAndFreezesPercentagesIntoAmounts() {
        val scenario = band().apply(SetFundDefault(FundCut(percent = 10)))
        gig(scenario, "empty").apply(SetFundAmount("empty", Money.ZERO, percent = 10))
        gig(scenario).apply(AddHolding("gig", "h", "bob", pln("1000"), DOOR)).apply(SetFundAmount("gig", Money.ZERO, percent = 10))
            .apply(SettleSuggestions("gig"))
            // The treasurer spends the 100, so the fund is empty and may be turned off.
            .apply(AddCost("c", "alice", pln("100"), DATE))
            .apply(AddFundEntry("spent", DATE, "Strings", pln("-100"), costId = "c"))
        scenario.apply(DisableBandFund, Actor.System)
        assertNull(scenario.band.fundDefault)
        assertNull(scenario.band.event("empty")!!.fundPercent)
        assertEquals(EventStatus.DRAFT, scenario.settle("empty").status)
        val gig = scenario.band.event("gig")!!
        assertNull(gig.fundPercent)
        assertEquals(pln("100"), gig.fundAmount, "the amount it came to, already handed over")
        // A late correction of the income no longer owes a fund that is off.
        scenario.apply(UpdateHolding("gig", "h", amount = Change(pln("1200"))))
    }

    @Test
    fun aDefaultSentAfterTheFundWasTurnedOffIsDroppedNotTheEvent() {
        val scenario = Scenario("Alice").event("x", "Alice")
        scenario.apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice")), fund = FundCut(percent = 10)))
        assertNull(scenario.band.event("e")!!.fundPercent)
    }
}
