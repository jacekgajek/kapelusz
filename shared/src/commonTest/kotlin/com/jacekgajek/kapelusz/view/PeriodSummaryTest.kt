package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.view.BandView
import com.jacekgajek.kapelusz.view.FundSource
import com.jacekgajek.kapelusz.view.FundTotals
import com.jacekgajek.kapelusz.view.PersonTotals
import com.jacekgajek.kapelusz.view.SummaryPeriod
import com.jacekgajek.kapelusz.view.periodSummary
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn

/** At [at] (epoch millis); 0: unknown, so a transfer counts on its gig's date. */
private fun Band.apply(vararg ops: Operation, at: Long = 0): Band = ops.fold(this) { band, op ->
    val result = Engine.apply(band, Actor.System, op, "op", at)
    (result as? ApplyResult.Applied ?: error("$op: $result")).band
}

private fun zl(amount: Int) = Money(amount * 100L)

/**
 * A settled winter gig (1000 collected, 100 to the fund, a guest on a fixed 100, my 200 travel cost refunded; the
 * rest 300 each), an open gig in February, Bob's 300 food cost refunded from the fund in January, and my 100 cost
 * still waiting. Bob paid 500 into the fund in November.
 */
private val band = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
    AddMember("bob", "Bob"),
    SetTreasurer("me"),
    AddFundEntry("e0", LocalDate(2025, 11, 1), "Membership fee", zl(500), paidBy = "bob"),
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

class PeriodSummaryTest :
    ExpectSpec({
        coroutineTestScope = true

        expect("all time") {
            val summary = periodSummary(BandView(band), me, null, null)
            withClue("only settled events") { summary.events shouldBe 1 }
            summary.collected shouldBe zl(1000)
            summary.paidOut shouldBe zl(700)
            summary.refundedAtEvents shouldBe zl(200)
            summary.toFund shouldBe zl(100)
            withClue("the parts add up to Collected") {
                (summary.paidOut + summary.refundedAtEvents + summary.toFund) shouldBe summary.collected
            }
            withClue("fully refunded: my travel at the gig, Bob's food from the fund") { summary.costs shouldBe zl(500) }
            summary.categories shouldBe listOf(CostCategory.FOOD to zl(300), CostCategory.TRAVEL to zl(200))
            summary.fund shouldBe
                FundTotals(
                    into = zl(600),
                    out = zl(300),
                    balance = zl(300),
                    // Who paid in, and whose costs it refunded.
                    intoBy = listOf(FundSource.Of(bob) to zl(500), FundSource.Events to zl(100)),
                    outBy = listOf(FundSource.Of(bob) to zl(300)),
                )
            summary.members shouldBe listOf(bob to PersonTotals(zl(300), zl(300)), me!! to PersonTotals(zl(300), zl(200)))
            summary.guests shouldBe PersonTotals(zl(100), Money.ZERO)
            summary.me shouldBe PersonTotals(zl(300), zl(200))
            summary.myWaiting shouldBe zl(100)
            summary.unsettled.map { it.id } shouldBe listOf("open")
        }

        expect("each on its own date") {
            val y2025 = periodSummary(BandView(band), me, LocalDate(2025, 1, 1), LocalDate(2025, 12, 31))
            y2025.events shouldBe 1
            y2025.costs shouldBe zl(200)
            y2025.fund shouldBe
                FundTotals(
                    into = zl(600),
                    out = Money.ZERO,
                    balance = zl(600),
                    intoBy = listOf(FundSource.Of(bob) to zl(500), FundSource.Events to zl(100)),
                )
            y2025.unsettled shouldBe emptyList()

            val y2026 = periodSummary(BandView(band), me, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
            y2026.events shouldBe 0
            y2026.categories shouldBe listOf(CostCategory.FOOD to zl(300))
            y2026.fund shouldBe
                FundTotals(into = Money.ZERO, out = zl(300), balance = zl(300), outBy = listOf(FundSource.Of(bob) to zl(300)))
            withClue("a refund from the fund on the entry's date") {
                y2026.members shouldBe listOf(bob to PersonTotals(Money.ZERO, zl(300)))
            }
            y2026.guests.shouldBeNull()
            y2026.me shouldBe PersonTotals.ZERO
            withClue("waiting is as of today, whatever the period") { y2026.myWaiting shouldBe zl(100) }
            y2026.unsettled.map { it.id } shouldBe listOf("open")
        }

        expect("a cost counts when fully refunded") {
            // Half of the equipment at a settled spring gig: refunded in part, not a cost of the period yet.
            val partly = band.apply(
                CreateEvent("spring", "Spring", LocalDate(2026, 3, 1), participants = listOf(Participant("me"))),
                AddHolding("spring", "h3", "me", zl(100), IncomeSource.DOOR),
                SetRefund("spring", "c3", zl(50)),
            )
            val summary = periodSummary(BandView(partly), me, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
            summary.costs shouldBe zl(300)
            withClue("the part paid back counts as refunded") { summary.me shouldBe PersonTotals(zl(50), zl(50)) }
            summary.myWaiting shouldBe zl(50)
        }

        expect("the fund covering an event is out and the balance is the fund page's") {
            val covered = band.apply(
                CreateEvent("covered", "Covered", LocalDate(2026, 4, 1), participants = listOf(Participant("me"), Participant("bob"))),
                AddHolding("covered", "h4", "me", zl(100), IncomeSource.DOOR),
                SetFundAmount("covered", zl(-100)),
                RecordTransfer("covered", "t4", BAND_FUND, "bob", zl(100)),
            )
            val y2026 = periodSummary(BandView(covered), me, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
            y2026.fund shouldBe
                FundTotals(
                    into = Money.ZERO,
                    out = zl(400),
                    balance = zl(200),
                    outBy = listOf(FundSource.Of(bob) to zl(300), FundSource.Events to zl(100)),
                )
            withClue("the fund covered part of the event") { y2026.toFund shouldBe zl(-100) }
            (y2026.paidOut + y2026.refundedAtEvents + y2026.toFund) shouldBe y2026.collected
            periodSummary(BandView(covered), me, null, null).fund?.balance shouldBe BandFund.balance(covered)
        }

        expect("a cost finished from the fund counts on the later date") {
            val finished = band.apply(
                CreateEvent("spring", "Spring", LocalDate(2026, 3, 1), participants = listOf(Participant("me"))),
                AddHolding("spring", "h3", "me", zl(100), IncomeSource.DOOR),
                SetRefund("spring", "c3", zl(50)),
                AddFundEntry("e2", LocalDate(2026, 5, 1), "Rest", zl(-50), costId = "c3"),
            )
            periodSummary(BandView(finished), me, LocalDate(2026, 3, 1), LocalDate(2026, 4, 30)).categories shouldBe emptyList()
            periodSummary(BandView(finished), me, LocalDate(2026, 5, 1), LocalDate(2026, 5, 31)).categories shouldBe
                listOf(CostCategory.EQUIPMENT to zl(100))
            periodSummary(BandView(finished), me, null, null).myWaiting shouldBe Money.ZERO
        }

        expect("only waiting is still an empty period") {
            val summary = periodSummary(BandView(band), me, LocalDate(2024, 1, 1), LocalDate(2024, 12, 31))
            summary.isEmpty shouldBe true
            summary.myWaiting shouldBe zl(100)
        }

        expect("an artist's unsettled line names only their events") {
            val withBobs = band.apply(
                CreateEvent("bobs", "Bob's", LocalDate(2026, 2, 2), participants = listOf(Participant("bob"))),
                AddHolding("bobs", "h5", "bob", zl(100), IncomeSource.DOOR),
                AddMember("ann", "Ann"),
                AddParticipant("bobs", "ann"),
            )
            periodSummary(BandView(withBobs), me, null, null).unsettled.map { it.id } shouldBe listOf("open", "bobs")
            periodSummary(BandView(withBobs), me, null, null, onlyEventsOf = "me").unsettled.map { it.id } shouldBe listOf("open")
        }

        expect("parts add up to collected when shares need rounding") {
            val odd = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
                AddMember("bob", "Bob"),
                AddMember("ann", "Ann"),
                CreateEvent(
                    "gig",
                    "Gig",
                    LocalDate(2026, 6, 1),
                    participants = listOf(Participant("me"), Participant("bob"), Participant("ann")),
                ),
                AddHolding("gig", "h", "me", Money(100_001), IncomeSource.DOOR),
                AddCost("c", "bob", Money(1_000), LocalDate(2026, 6, 1)),
                SetRefund("gig", "c", Money(1_000)),
                SettleSuggestions("gig"),
            )
            val summary = periodSummary(BandView(odd), odd.member("me"), null, null)
            withClue("settled by the suggested transfers") { summary.events shouldBe 1 }
            summary.collected shouldBe Money(100_001)
            (summary.paidOut + summary.refundedAtEvents + summary.toFund) shouldBe summary.collected
        }

        expect("the fund's part handed over before the gig counts when recorded") {
            val gig = LocalDate(2026, 10, 27)
            val early = band.apply(
                CreateEvent("next", "Next", gig, participants = listOf(Participant("me"))),
                AddHolding("next", "h6", "me", zl(100), IncomeSource.DOOR),
                SetFundAmount("next", zl(100)),
            ).apply(
                RecordTransfer("next", "t6", "me", BAND_FUND, zl(100)),
                at = LocalDate(2026, 10, 26).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 3_600_000,
            )
            val untilToday =
                periodSummary(BandView(early), me, LocalDate(2026, 1, 1), LocalDate(2026, 10, 26), timeZone = TimeZone.UTC).fund
            untilToday shouldBe
                FundTotals(
                    into = zl(100),
                    out = zl(300),
                    balance = zl(400),
                    intoBy = listOf(FundSource.Events to zl(100)),
                    outBy = listOf(FundSource.Of(bob) to zl(300)),
                )
            withClue("as on the fund's page") { untilToday?.balance shouldBe BandFund.balance(early) }
        }

        expect("a hand over recorded after the period is not in it") {
            fun at(date: LocalDate) = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 3_600_000
            val late = band.apply(
                CreateEvent("may", "May", LocalDate(2026, 5, 1), participants = listOf(Participant("me"), Participant("bob"))),
                AddHolding("may", "h7", "me", zl(100), IncomeSource.DOOR),
                SetFundAmount("may", zl(-100)),
            ).apply(RecordTransfer("may", "t7", BAND_FUND, "bob", zl(100)), at = at(LocalDate(2026, 6, 2)))
            // Out of the fund on 2 June, though the gig was in May.
            val may = periodSummary(BandView(late), me, LocalDate(2026, 5, 1), LocalDate(2026, 5, 31), timeZone = TimeZone.UTC).fund
            may shouldBe FundTotals(into = Money.ZERO, out = Money.ZERO, balance = zl(300))
            val june = periodSummary(BandView(late), me, LocalDate(2026, 6, 1), LocalDate(2026, 6, 30), timeZone = TimeZone.UTC).fund
            june shouldBe FundTotals(into = Money.ZERO, out = zl(100), balance = zl(200), outBy = listOf(FundSource.Events to zl(100)))
            periodSummary(BandView(late), me, null, null).fund?.balance shouldBe BandFund.balance(late)
        }

        expect("a hand over is dated in the viewer's time zone") {
            // 23:30 UTC on 31 May is already 1 June in Poland (UTC+2 in summer; a fixed offset, as the web tests have no
            // time zone database).
            val atNight =
                LocalDate(2026, 5, 31).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 23 * 3_600_000 + 30 * 60_000
            val night = band.apply(
                CreateEvent("gig2", "Gig", LocalDate(2026, 5, 31), participants = listOf(Participant("me"))),
                AddHolding("gig2", "h8", "me", zl(100), IncomeSource.DOOR),
                SetFundAmount("gig2", zl(100)),
            ).apply(RecordTransfer("gig2", "t8", "me", BAND_FUND, zl(100)), at = atNight)
            val june = LocalDate(2026, 6, 1) to LocalDate(2026, 6, 30)
            val poland = UtcOffset(hours = 2).asTimeZone()
            periodSummary(BandView(night), me, june.first, june.second, timeZone = poland).fund?.into shouldBe zl(100)
            periodSummary(BandView(night), me, june.first, june.second, timeZone = TimeZone.UTC).fund?.into shouldBe Money.ZERO
        }

        expect("no fund, no fund totals") {
            val noFund = Band.create("b", "Band", "PLN", "me", "Me", "u")
            periodSummary(BandView(noFund), noFund.member("me"), null, null).fund.shouldBeNull()
            periodSummary(BandView(noFund), noFund.member("me"), null, null).isEmpty shouldBe true
        }

        expect("periods") {
            val today = LocalDate(2026, 10, 26)
            SummaryPeriod.Last12Months.range(today) shouldBe (LocalDate(2025, 10, 27) to today)
            SummaryPeriod.ThisYear.range(today) shouldBe (LocalDate(2026, 1, 1) to today)
            SummaryPeriod.LastYear.range(today) shouldBe (LocalDate(2025, 1, 1) to LocalDate(2025, 12, 31))
            SummaryPeriod.AllTime.range(today) shouldBe (null to null)
        }
    })
