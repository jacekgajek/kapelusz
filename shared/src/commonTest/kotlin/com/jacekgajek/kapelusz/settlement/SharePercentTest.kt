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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** A participant's share as a percentage of the profit. */
class SharePercentTest :
    ExpectSpec({

        expect("a percentage replaces the share and the rest is split by weight") {
            val s = gig().apply(SetFixedShare("gig", "alice", null, percent = 50)).settle("gig")
            s.targets shouldBe mapOf("alice" to pln("500"), "bob" to pln("250"), "carol" to pln("250"))
            s.percentTotal shouldBe pln("500")
            s.line("alice")!!.percent shouldBe 50
            s.problems.shouldBeEmpty()
        }

        expect("it is of the same profit as the fund's percentage") {
            val s = gig().apply(SetFundAmount("gig", Money.ZERO, percent = 10))
                .apply(SetFixedShare("gig", "alice", null, percent = 50)).settle("gig")
            // 10% + 50% of 1000: the fund 100, Alice 500, and 400 for Bob and Carol.
            s.fundAmount shouldBe pln("100")
            s.targets shouldBe mapOf("alice" to pln("500"), "bob" to pln("200"), "carol" to pln("200"), "band-fund" to pln("100"))
        }

        expect("it is after refunds and includes what the fund adds") {
            val scenario = gig("600")
                .apply(AddCost("c", "carol", pln("200"), DATE, incurredForEventId = "gig"))
                .apply(SetRefund("gig", "c", pln("200")))
                .apply(SetFixedShare("gig", "alice", null, percent = 50))
            withClue("50% of 600 − 200") { scenario.settle("gig").line("alice")!!.share shouldBe pln("200") }
            // The fund covers the refund: as if there were no cost at all.
            scenario.apply(SetFundAmount("gig", pln("-200")))
            scenario.settle("gig").line("alice")!!.share shouldBe pln("300")
        }

        expect("it is rounded down and the grosze left go to the shares") {
            val s = gig("1000.01").apply(SetFixedShare("gig", "alice", null, percent = 50)).settle("gig")
            s.line("alice")!!.share shouldBe pln("500")
            s.line("bob")!!.share + s.line("carol")!!.share shouldBe pln("500.01")
        }

        expect("with everyone on percentages adding up to 100 the grosze left go to them") {
            val scenario = gig("1000.01")
                .apply(SetFixedShare("gig", "alice", null, percent = 50))
                .apply(SetFixedShare("gig", "bob", null, percent = 30))
                .apply(SetFixedShare("gig", "carol", null, percent = 20))
            val s = scenario.settle("gig")
            s.problems.shouldBeEmpty()
            s.targets shouldBe mapOf("alice" to pln("500.01"), "bob" to pln("300"), "carol" to pln("200"))
            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("money nobody is to get is a problem") {
            val s = gig()
                .apply(SetFixedShare("gig", "alice", null, percent = 30))
                .apply(SetFixedShare("gig", "bob", pln("100")))
                .apply(SetFixedShare("gig", "carol", null, percent = 20))
                .settle("gig")
            s.problems shouldBe setOf(SettlementProblem.NO_SHARES)
        }

        expect("over 100 percent is a problem not a loss") {
            val scenario = gig().apply(SetFundAmount("gig", Money.ZERO, percent = 10))
                .apply(SetFixedShare("gig", "alice", null, percent = 50))
                .apply(SetFixedShare("gig", "bob", null, percent = 45))
            val s = scenario.settle("gig")
            s.problems shouldBe setOf(SettlementProblem.OVER_PERCENT)
            s.status shouldBe EventStatus.OPEN
            s.suggestions.shouldBeEmpty()
        }

        expect("over 100 percent does not hide a real loss") {
            val s = gig("1000")
                .apply(AddCost("c", "carol", pln("1200"), DATE, incurredForEventId = "gig"))
                .apply(SetRefund("gig", "c", pln("1200")))
                .apply(SetFundAmount("gig", Money.ZERO, percent = 10))
                .apply(SetFixedShare("gig", "alice", null, percent = 95))
                .settle("gig")
            s.problems shouldBe setOf(SettlementProblem.OVER_PERCENT, SettlementProblem.LOSS)
            s.status shouldBe EventStatus.LOSS
            s.shortfall shouldBe pln("200")
        }

        expect("a loss gives a percentage nothing") {
            val s = gig("100")
                .apply(AddCost("c", "carol", pln("300"), DATE, incurredForEventId = "gig"))
                .apply(SetRefund("gig", "c", pln("300")))
                .apply(SetFixedShare("gig", "alice", null, percent = 50))
                .settle("gig")
            s.line("alice")!!.share shouldBe Money.ZERO
            s.problems shouldBe setOf(SettlementProblem.LOSS)
            s.shortfall shouldBe pln("200")
        }

        expect("the weight underneath comes back and zero percent is no share") {
            val scenario = gig().apply(SetWeight("gig", "alice", 150)).apply(SetFixedShare("gig", "alice", null, percent = 40))
            scenario.band.event("gig")!!.participant("alice")!!.let {
                it.percent shouldBe 40
                it.weight shouldBe 150
            }
            scenario.apply(SetFixedShare("gig", "alice", pln("100")))
            scenario.band.event("gig")!!.participant("alice")!!.let {
                withClue("a fixed amount replaces the percentage") { it.percent.shouldBeNull() }
                it.fixedShare shouldBe pln("100")
            }
            scenario.apply(SetFixedShare("gig", "alice", null))
            scenario.band.event("gig")!!.participant("alice")!!.let { it.byWeight && it.weight == 150 } shouldBe true
            scenario.apply(SetFixedShare("gig", "alice", null, percent = 0))
            scenario.band.event("gig")!!.participant("alice")!!.let { it.byWeight && it.weight == 0 } shouldBe true
        }

        expect("it is checked") {
            val scenario = gig()
            scenario.rejected(SetFixedShare("gig", "alice", null, percent = 101)).reason shouldBe RejectionReason.INVALID_SHARE
            scenario.rejected(AddGuest("gig", "dave", "Dave", percent = -5)).reason shouldBe RejectionReason.INVALID_SHARE
            scenario.rejected(SetFixedShare("gig", "alice", pln("100"), percent = 10)).reason shouldBe RejectionReason.INVALID_INPUT
            scenario.rejected(SetMemberShare("bob", 100, pln("100"), percent = 10)).reason shouldBe RejectionReason.INVALID_INPUT
            scenario.apply(AddGuest("gig", "dave", "Dave", percent = 20))
            scenario.band.event("gig")!!.participant("dave")!!.percent shouldBe 20
        }

        expect("a member's default can be a percentage") {
            val scenario = Scenario("Alice", "Bob").event("a", "Alice", "Bob").event("b", "Alice")
            scenario.apply(SetMemberShare("bob", 100, null, events = listOf("a"), percent = 20))
            scenario.band.member("bob")!!.sharePercent shouldBe 20
            scenario.band.event("a")!!.participant("bob")!!.percent shouldBe 20
            MemberShares.stillDefault(BandView(scenario.band), scenario.band.member("bob")!!).map { it.id } shouldBe listOf("a")
            scenario.apply(AddParticipant("b", "bob", 100, percent = 20))
            scenario.band.member("bob")!!.asParticipant().percent shouldBe 20

            // Back to a weight: the events chosen go back to it too.
            val before = scenario.band
            val op: Operation = SetMemberShare("bob", 75, null, events = listOf("a", "b"))
            scenario.apply(op)
            scenario.band.member("bob")!!.sharePercent.shouldBeNull()
            scenario.band.event("b")!!.participant("bob")!!.let { it.byWeight && it.weight == 75 } shouldBe true
            scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)))
            scenario.band.member("bob")!!.sharePercent shouldBe 20
            scenario.band.event("b")!!.participant("bob")!!.percent shouldBe 20
        }
    })

private fun gig(income: String = "1000") = Scenario("Alice", "Bob", "Carol").apply(SetTreasurer("alice"))
    .event("gig", "Alice", "Bob", "Carol")
    .apply(AddHolding("gig", "h", "bob", pln(income), DOOR))
