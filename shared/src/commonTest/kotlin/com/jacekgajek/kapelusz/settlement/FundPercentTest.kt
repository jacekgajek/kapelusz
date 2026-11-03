package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
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
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.view.BandView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The band fund as a percentage of an event's profit, and the band's default for new events. */
class FundPercentTest :
    ExpectSpec({

        expect("a percentage is of the profit after refunds rounded down to whole units") {
            val scenario = gig()
                .apply(AddHolding("gig", "h", "alice", pln("4600.00"), DOOR))
                .apply(AddCost("c", "bob", pln("32.11"), DATE, incurredForEventId = "gig"))
                .apply(SetRefund("gig", "c", pln("32.11")))
                .apply(SetFundAmount("gig", Money.ZERO, percent = 10))
            // 10% of 4567.89 is 456.789: the fund gets 456, the players share the rest.
            val s = scenario.settle("gig")
            s.fundAmount shouldBe pln("456")
            s.line(BAND_FUND)!!.target shouldBe pln("456")
            s.net shouldBe pln("4111.89")
            s.line("alice")!!.share + s.line("bob")!!.share shouldBe pln("2055.95") + pln("2055.94")
        }

        expect("the percentage follows the income and takes nothing from a loss") {
            val scenario = gig().apply(SetFundAmount("gig", Money.ZERO, percent = 20))
            withClue("no income yet") { scenario.settle("gig").fundAmount shouldBe Money.ZERO }
            scenario.settle("gig").status shouldBe EventStatus.DRAFT
            scenario.apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
            scenario.settle("gig").fundAmount shouldBe pln("200")
            scenario.apply(AddCost("c", "bob", pln("1500"), DATE, incurredForEventId = "gig")).apply(SetRefund("gig", "c", pln("1500")))
            withClue("a loss gives the fund nothing") { scenario.settle("gig").fundAmount shouldBe Money.ZERO }
        }

        expect("a percent event settles with the fund and an amount replaces the percentage") {
            val scenario = gig().apply(AddHolding("gig", "h", "bob", pln("1000"), DOOR))
                .apply(SetFundAmount("gig", Money.ZERO, percent = 10))
            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            BandFund.balance(scenario.band) shouldBe pln("100")

            scenario.apply(SetFundAmount("gig", pln("-30")))
            val event = scenario.band.event("gig")!!
            event.fundPercent.shouldBeNull()
            event.fund shouldBe pln("-30")
        }

        expect("zero percent is nothing and percentages are checked") {
            val scenario = gig().apply(SetFundAmount("gig", pln("50"), percent = 0))
            val event = scenario.band.event("gig")!!
            event.fundPercent.shouldBeNull()
            withClue("the amount is ignored with a percentage") { event.fundAmount shouldBe Money.ZERO }
            scenario.rejected(SetFundAmount("gig", Money.ZERO, percent = 101)).reason shouldBe RejectionReason.INVALID_SHARE
            scenario.rejected(SetFundAmount("gig", Money.ZERO, percent = -5)).reason shouldBe RejectionReason.INVALID_SHARE
            val noFund = Scenario("Alice").event("e", "Alice")
            noFund.rejected(SetFundAmount("e", Money.ZERO, percent = 10)).reason shouldBe RejectionReason.NO_TREASURER
        }

        expect("new events get the default they are created with") {
            val scenario = band().apply(SetFundDefault(FundCut(percent = 15)))
            scenario.band.fundDefault shouldBe FundCut(percent = 15)
            scenario.apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice")), fund = scenario.band.fundDefault))
            scenario.band.event("e")!!.fundPercent shouldBe 15
            scenario.apply(CreateEvent("f", "Gig", DATE, participants = listOf(Participant("alice")), fund = FundCut(amount = pln("300"))))
            scenario.band.event("f")!!.fundAmount shouldBe pln("300")
            scenario.rejected(
                CreateEvent("g", "Gig", DATE, fund = FundCut(amount = pln("300"), percent = 10)),
            ).reason shouldBe RejectionReason.INVALID_INPUT
            scenario.rejected(SetFundDefault(FundCut(amount = pln("-1")))).reason shouldBe RejectionReason.INVALID_AMOUNT
            Scenario("Alice").rejected(SetFundDefault(FundCut(percent = 10))).reason shouldBe RejectionReason.NO_TREASURER
        }

        expect("a changed default goes to the events chosen that can still take it") {
            val scenario = band()
            gig(scenario, "open")
            gig(scenario, "done").apply(AddHolding("done", "h", "alice", pln("100"), DOOR)).apply(SettleSuggestions("done"))
            gig(scenario, "other").apply(SetFundAmount("other", pln("40")))
            withClue("settled and changed events are not offered") {
                BandFund.stillDefault(BandView(scenario.band)).map { it.id } shouldBe listOf("open")
            }

            scenario.apply(SetFundDefault(FundCut(percent = 10), events = listOf("open", "done", "gone")))
            scenario.band.event("open")!!.fundPercent shouldBe 10
            withClue("settled since it was chosen: left alone") { scenario.band.event("done")!!.fundPercent.shouldBeNull() }
            BandFund.stillDefault(BandView(scenario.band)).map { it.id } shouldBe listOf("open")

            scenario.apply(SetFundDefault(FundCut(percent = 0)))
            withClue("0% is no default") { scenario.band.fundDefault.shouldBeNull() }
        }

        expect("only leaders set the default") {
            val scenario = band()
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.rejected(SetFundDefault(FundCut(percent = 10)), bob).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.apply(SetFundDefault(FundCut(percent = 10)), scenario.userFor("carol", Role.LEAD))
        }

        expect("undo brings back the old default and events") {
            val scenario = band()
            gig(scenario)
            val before = scenario.band
            val op: Operation = SetFundDefault(FundCut(percent = 10), events = listOf("gig"))
            scenario.apply(op)
            scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)))
            scenario.band.fundDefault.shouldBeNull()
            scenario.band.event("gig")!!.fundPercent.shouldBeNull()
        }

        expect("turning the fund off drops its default and freezes percentages into amounts") {
            val scenario = band().apply(SetFundDefault(FundCut(percent = 10)))
            gig(scenario, "empty").apply(SetFundAmount("empty", Money.ZERO, percent = 10))
            gig(scenario).apply(AddHolding("gig", "h", "bob", pln("1000"), DOOR)).apply(SetFundAmount("gig", Money.ZERO, percent = 10))
                .apply(SettleSuggestions("gig"))
                // The treasurer spends the 100, so the fund is empty and may be turned off.
                .apply(AddCost("c", "alice", pln("100"), DATE))
                .apply(AddFundEntry("spent", DATE, "Strings", pln("-100"), costId = "c"))
            scenario.apply(DisableBandFund, Actor.System)
            scenario.band.fundDefault.shouldBeNull()
            scenario.band.event("empty")!!.fundPercent.shouldBeNull()
            scenario.settle("empty").status shouldBe EventStatus.DRAFT
            val gig = scenario.band.event("gig")!!
            gig.fundPercent.shouldBeNull()
            withClue("the amount it came to, already handed over") { gig.fundAmount shouldBe pln("100") }
            // A late correction of the income no longer owes a fund that is off.
            scenario.apply(UpdateHolding("gig", "h", amount = Change(pln("1200"))))
        }

        expect("a default sent after the fund was turned off is dropped not the event") {
            val scenario = Scenario("Alice").event("x", "Alice")
            scenario.apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice")), fund = FundCut(percent = 10)))
            scenario.band.event("e")!!.fundPercent.shouldBeNull()
        }
    })

private fun band() = Scenario("Alice", "Bob", "Carol").apply(SetTreasurer("alice"))

private fun gig(scenario: Scenario = band(), id: String = "gig") = scenario.event(id, "Alice", "Bob")
