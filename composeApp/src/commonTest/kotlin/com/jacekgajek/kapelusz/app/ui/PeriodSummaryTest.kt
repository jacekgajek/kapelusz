package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.ui.model.FundTotals
import com.jacekgajek.kapelusz.app.ui.model.summaryPeriod
import com.jacekgajek.kapelusz.app.ui.model.withSummaryPeriod
import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.app.ui.model.PersonTotals
import com.jacekgajek.kapelusz.app.ui.model.SummaryPeriod
import com.jacekgajek.kapelusz.app.ui.model.periodSummary
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeriodSummaryTest {

    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        val result = Engine.apply(band, Actor.System, op, "op", 1)
        (result as? ApplyResult.Applied ?: error("$op: $result")).band
    }

    private fun zl(amount: Int) = Money(amount * 100L)

    /**
     * A settled winter gig (1000 collected, 100 to the fund, a guest on a fixed 100, my 200 travel cost refunded; the
     * rest 300 each), an open gig in February, Bob's 300 food cost refunded from the fund in January, and my 100 cost
     * still waiting.
     */
    private val band = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
        AddMember("bob", "Bob"),
        SetTreasurer("me"),
        AddFundEntry("e0", LocalDate(2025, 11, 1), "Deposit", zl(500)),
        CreateEvent("winter", "Winter", LocalDate(2025, 12, 10), participants = listOf(Participant("me"), Participant("bob"))),
        AddGuest("winter", "g", "Guest", fixedShare = zl(100)),
        AddHolding("winter", "h", "bob", zl(1000), IncomeSource.DOOR),
        AddCost("c1", "me", zl(200), LocalDate(2025, 12, 1), category = CostCategory.TRAVEL),
        SetRefund("winter", "c1", zl(200)),
        SetFundAmount("winter", zl(100)),
        RecordTransfer("winter", "t1", "bob", "me", zl(500)),
        RecordTransfer("winter", "t2", "bob", BAND_FUND, zl(100)),
        RecordTransfer("winter", "t3", "bob", "g", zl(100)),
        CreateEvent("open", "Open", LocalDate(2026, 2, 1), participants = listOf(Participant("me"), Participant("bob"))),
        AddHolding("open", "h2", "me", zl(500), IncomeSource.DOOR),
        AddCost("c2", "bob", zl(300), LocalDate(2026, 1, 15), category = CostCategory.FOOD),
        AddFundEntry("e1", LocalDate(2026, 1, 20), "Food", zl(-300), costId = "c2"),
        AddCost("c3", "me", zl(100), LocalDate(2026, 1, 10), category = CostCategory.EQUIPMENT),
    )
    private val me = band.member("me")
    private val bob = band.member("bob")!!

    @Test
    fun allTime() = runTest {
        val summary = periodSummary(band, me, null, null)
        assertEquals(1, summary.events, "only settled events")
        assertEquals(zl(1000), summary.collected)
        assertEquals(zl(700), summary.paidOut)
        assertEquals(zl(200), summary.refundedAtEvents)
        assertEquals(zl(100), summary.toFund)
        assertEquals(summary.collected, summary.paidOut + summary.refundedAtEvents + summary.toFund, "the parts add up to Collected")
        assertEquals(zl(500), summary.costs, "fully refunded: my travel at the gig, Bob's food from the fund")
        assertEquals(listOf(CostCategory.FOOD to zl(300), CostCategory.TRAVEL to zl(200)), summary.categories)
        assertEquals(FundTotals(into = zl(600), out = zl(300), balance = zl(300)), summary.fund)
        assertEquals(listOf(bob to PersonTotals(zl(300), zl(300)), me!! to PersonTotals(zl(300), zl(200))), summary.members)
        assertEquals(PersonTotals(zl(100), Money.ZERO), summary.guests)
        assertEquals(PersonTotals(zl(300), zl(200)), summary.me)
        assertEquals(zl(100), summary.myWaiting)
        assertEquals(listOf("open"), summary.unsettled.map { it.id })
    }

    @Test
    fun eachOnItsOwnDate() = runTest {
        val y2025 = periodSummary(band, me, LocalDate(2025, 1, 1), LocalDate(2025, 12, 31))
        assertEquals(1, y2025.events)
        assertEquals(zl(200), y2025.costs)
        assertEquals(FundTotals(into = zl(600), out = Money.ZERO, balance = zl(600)), y2025.fund)
        assertEquals(emptyList(), y2025.unsettled)

        val y2026 = periodSummary(band, me, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
        assertEquals(0, y2026.events)
        assertEquals(listOf(CostCategory.FOOD to zl(300)), y2026.categories)
        assertEquals(FundTotals(into = Money.ZERO, out = zl(300), balance = zl(300)), y2026.fund)
        assertEquals(listOf(bob to PersonTotals(Money.ZERO, zl(300))), y2026.members, "a refund from the fund on the entry's date")
        assertNull(y2026.guests)
        assertEquals(PersonTotals.ZERO, y2026.me)
        assertEquals(zl(100), y2026.myWaiting, "waiting is as of today, whatever the period")
        assertEquals(listOf("open"), y2026.unsettled.map { it.id })
    }

    @Test
    fun aCostCountsWhenFullyRefunded() = runTest {
        // Half of the equipment at a settled spring gig: refunded in part, not a cost of the period yet.
        val partly = band.apply(
            CreateEvent("spring", "Spring", LocalDate(2026, 3, 1), participants = listOf(Participant("me"))),
            AddHolding("spring", "h3", "me", zl(100), IncomeSource.DOOR),
            SetRefund("spring", "c3", zl(50)),
        )
        val summary = periodSummary(partly, me, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
        assertEquals(zl(300), summary.costs)
        assertEquals(PersonTotals(zl(50), zl(50)), summary.me, "the part paid back counts as refunded")
        assertEquals(zl(50), summary.myWaiting)
    }

    @Test
    fun theFundCoveringAnEventIsOutAndTheBalanceIsTheFundPages() = runTest {
        val covered = band.apply(
            CreateEvent("covered", "Covered", LocalDate(2026, 4, 1), participants = listOf(Participant("me"), Participant("bob"))),
            AddHolding("covered", "h4", "me", zl(100), IncomeSource.DOOR),
            SetFundAmount("covered", zl(-100)),
            RecordTransfer("covered", "t4", BAND_FUND, "bob", zl(100)),
        )
        val y2026 = periodSummary(covered, me, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
        assertEquals(FundTotals(into = Money.ZERO, out = zl(400), balance = zl(200)), y2026.fund)
        assertEquals(zl(-100), y2026.toFund, "the fund covered part of the event")
        assertEquals(y2026.collected, y2026.paidOut + y2026.refundedAtEvents + y2026.toFund)
        assertEquals(BandFund.balance(covered), periodSummary(covered, me, null, null).fund?.balance)
    }

    @Test
    fun aCostFinishedFromTheFundCountsOnTheLaterDate() = runTest {
        val finished = band.apply(
            CreateEvent("spring", "Spring", LocalDate(2026, 3, 1), participants = listOf(Participant("me"))),
            AddHolding("spring", "h3", "me", zl(100), IncomeSource.DOOR),
            SetRefund("spring", "c3", zl(50)),
            AddFundEntry("e2", LocalDate(2026, 5, 1), "Rest", zl(-50), costId = "c3"),
        )
        assertEquals(emptyList(), periodSummary(finished, me, LocalDate(2026, 3, 1), LocalDate(2026, 4, 30)).categories)
        assertEquals(
            listOf(CostCategory.EQUIPMENT to zl(100)),
            periodSummary(finished, me, LocalDate(2026, 5, 1), LocalDate(2026, 5, 31)).categories,
        )
        assertEquals(Money.ZERO, periodSummary(finished, me, null, null).myWaiting)
    }

    @Test
    fun onlyWaitingIsStillAnEmptyPeriod() = runTest {
        val summary = periodSummary(band, me, LocalDate(2024, 1, 1), LocalDate(2024, 12, 31))
        assertEquals(true, summary.isEmpty)
        assertEquals(zl(100), summary.myWaiting)
    }

    @Test
    fun anArtistsUnsettledLineNamesOnlyTheirEvents() = runTest {
        val withBobs = band.apply(
            CreateEvent("bobs", "Bob's", LocalDate(2026, 2, 2), participants = listOf(Participant("bob"))),
            AddHolding("bobs", "h5", "bob", zl(100), IncomeSource.DOOR),
            AddMember("ann", "Ann"),
            AddParticipant("bobs", "ann"),
        )
        assertEquals(listOf("open", "bobs"), periodSummary(withBobs, me, null, null).unsettled.map { it.id })
        assertEquals(listOf("open"), periodSummary(withBobs, me, null, null, onlyEventsOf = "me").unsettled.map { it.id })
    }

    @Test
    fun periodsAreRememberedPerBandAndAnUnreadableOneIsForgotten() {
        val custom = SummaryPeriod.Custom(LocalDate(2025, 9, 1), LocalDate(2026, 8, 31))
        val settings = AppSettings().withSummaryPeriod("b", custom)
        val stored = KapeluszJson.decodeFromString(AppSettings.serializer(), KapeluszJson.encodeToString(AppSettings.serializer(), settings))
        assertEquals(custom, stored.summaryPeriod("b"))
        assertEquals(SummaryPeriod.Last12Months, stored.summaryPeriod("other"))
        assertEquals(SummaryPeriod.Last12Months, AppSettings(summaryPeriods = mapOf("b" to "{\"type\":\"season\"}")).summaryPeriod("b"))
    }

    @Test
    fun noFundNoFundTotals() = runTest {
        val noFund = Band.create("b", "Band", "PLN", "me", "Me", "u")
        assertNull(periodSummary(noFund, noFund.member("me"), null, null).fund)
        assertEquals(true, periodSummary(noFund, noFund.member("me"), null, null).isEmpty)
    }

    @Test
    fun periods() {
        val today = LocalDate(2026, 10, 26)
        assertEquals(LocalDate(2025, 10, 27) to today, SummaryPeriod.Last12Months.range(today))
        assertEquals(LocalDate(2026, 1, 1) to today, SummaryPeriod.ThisYear.range(today))
        assertEquals(LocalDate(2025, 1, 1) to LocalDate(2025, 12, 31), SummaryPeriod.LastYear.range(today))
        assertEquals(null to null, SummaryPeriod.AllTime.range(today))
    }
}
