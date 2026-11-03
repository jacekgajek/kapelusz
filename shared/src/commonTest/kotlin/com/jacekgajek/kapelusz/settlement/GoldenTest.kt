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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun firstBand() = Scenario("Alice", "Bob", "Carol", "Dave", "Eve", "Frank")

private fun secondBand() = Scenario("Carol", "Grace", "Heidi", "Dave", "Ivan")

/** G1: even split, two members' costs refunded, a roster member not taking part. */
private fun g1() = firstBand()
    .event("oakfield", "Alice", "Bob", "Carol", "Dave", "Frank")
    .apply(AddCost("c-carol", "carol", pln("30"), DATE, incurredForEventId = "oakfield"))
    .apply(AddCost("c-dave", "dave", pln("322"), DATE, incurredForEventId = "oakfield"))
    .apply(AddHolding("oakfield", "h1", "dave", pln("1000"), DOOR))

/** G2: guest at 0% who collected money, several holders, absent member. */
private fun g2() = firstBand()
    .event("millbrook", "Alice", "Bob", "Carol", "Eve", guests = listOf("Helper 2" to 0))
    .apply(AddHolding("millbrook", "h1", "alice", pln("500"), DOOR))
    .apply(AddHolding("millbrook", "h2", "bob", pln("500"), DOOR))
    .apply(AddHolding("millbrook", "h3", "carol", pln("500"), DOOR))
    .apply(AddHolding("millbrook", "h4", "helper-2", pln("500"), DOOR))

/** G3: several income sources with notes, all consumed by one refund: settled without any transfer. */
private fun g3() = secondBand()
    .event("carried-over", "Carol", "Grace", "Heidi", "Dave", "Ivan")
    .apply(AddHolding("carried-over", "h1", "carol", pln("1870"), CONTRACT))
    .apply(AddHolding("carried-over", "h2", "carol", pln("200"), SPONSORSHIP, "sponsorship via Grace"))

/** G5: whole income to the band fund, collected by a non-treasurer. */
private fun g5() = secondBand()
    .apply(SetTreasurer("carol"))
    .event("four-lanterns", "Carol", "Grace", "Heidi", "Dave", "Ivan")
    .apply(AddHolding("four-lanterns", "h1", "dave", pln("710"), HAT, "hat at Four Lanterns"))
    .apply(SetFundAmount("four-lanterns", pln("710")))

/** Golden cases from, based on the (anonymized) spreadsheets. */
class GoldenTest :
    ExpectSpec({

        expect("G1 even split with refunds") {
            val s = g1().settle("oakfield")
            s.net shouldBe pln("648")
            s.targets shouldBe
                mapOf(
                    "alice" to pln("129.60"),
                    "bob" to pln("129.60"),
                    "carol" to pln("159.60"),
                    "dave" to pln("451.60"),
                    "frank" to pln("129.60"),
                )
            s.status shouldBe EventStatus.OPEN
            s.suggestions shouldBe
                listOf(
                    SuggestedTransfer("dave", "carol", pln("159.60")),
                    SuggestedTransfer("dave", "alice", pln("129.60")),
                    SuggestedTransfer("dave", "bob", pln("129.60")),
                    SuggestedTransfer("dave", "frank", pln("129.60")),
                )
            val settled = g1().apply(SettleSuggestions("oakfield"))
            settled.settle("oakfield").status shouldBe EventStatus.SETTLED
        }

        expect("G2 guest holder with zero share") {
            val s = g2().settle("millbrook")
            s.targets shouldBe
                mapOf("alice" to pln("500"), "bob" to pln("500"), "carol" to pln("500"), "eve" to pln("500"), "helper-2" to pln("0"))
            s.suggestions shouldBe listOf(SuggestedTransfer("helper-2", "eve", pln("500")))
        }

        expect("G3 income consumed by refund") {
            val scenario = g3().apply(AddCost("c1", "carol", pln("2070"), DATE, incurredForEventId = "carried-over"))
            val s = scenario.settle("carried-over")
            s.net shouldBe pln("0")
            s.line("carol")!!.target shouldBe pln("2070")
            s.lines.filter { it.partyId != "carol" }.all { it.target.isZero } shouldBe true
            s.status shouldBe EventStatus.SETTLED
            s.suggestions.shouldBeEmpty()
        }

        // G4: one cost repaid in parts across three events.
        expect("G4 amortization across events") {
            val all = arrayOf("Carol", "Grace", "Heidi", "Dave", "Ivan")
            val scenario = g3()
                .apply(AddCost("gear", "carol", pln("4970"), DATE))
                .apply(SetRefund("carried-over", "gear", pln("2070")))
            fun cost() = scenario.band.cost("gear")!!
            BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.PARTLY_REFUNDED
            CostLedger.outstanding(scenario.band, cost()) shouldBe pln("2900")

            scenario.event("southgate", *all)
                .apply(AddHolding("southgate", "h1", "carol", pln("1000"), CONTRACT))
            CostLedger.pendingFromEarlier(scenario.band, scenario.band.event("southgate")!!).map { it.id } shouldBe listOf("gear")
            scenario.apply(SetRefund("southgate", "gear", pln("1000")))
            CostLedger.outstanding(scenario.band, cost()) shouldBe pln("1900")

            scenario.event("grand-hotel", *all)
                .apply(AddHolding("grand-hotel", "h1", "carol", pln("400"), MERCH))
                .apply(AddHolding("grand-hotel", "h2", "carol", pln("1500"), CONTRACT))
                .apply(SetRefund("grand-hotel", "gear", pln("1900")))
            BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.REFUNDED
            listOf("carried-over", "southgate", "grand-hotel").forEach {
                withClue(it) { scenario.settle(it).status shouldBe EventStatus.SETTLED }
            }
        }

        expect("G5 whole income to fund") {
            val s = g5().settle("four-lanterns")
            s.net shouldBe pln("0")
            s.lines.filter { it.partyId != BAND_FUND }.all { it.target.isZero } shouldBe true
            s.suggestions shouldBe listOf(SuggestedTransfer("dave", BAND_FUND, pln("710")))
        }

        // G6: the treasurer collected the fund money themselves; fund totals across G5 and G6.
        expect("G6 treasurer keeps fund money") {
            val scenario = g5()
                .event("elmwood", "Carol", "Grace", "Dave", "Ivan")
                .apply(AddHolding("elmwood", "h1", "carol", pln("813"), CONTRACT))
                .apply(SetFundAmount("elmwood", pln("813")))
            scenario.settle("elmwood").suggestions shouldBe listOf(SuggestedTransfer("carol", BAND_FUND, pln("813")))
            BandFund.notHandedOver(scenario.band) shouldBe pln("1523")
            BandFund.balance(scenario.band) shouldBe pln("0")

            scenario.apply(SettleSuggestions("four-lanterns")).apply(SettleSuggestions("elmwood"))
            BandFund.balance(scenario.band) shouldBe pln("1523")
            BandFund.notHandedOver(scenario.band) shouldBe pln("0")
            scenario.settle("elmwood").status shouldBe EventStatus.SETTLED
        }

        // G7: a single holder owes several members, one member sits out.
        expect("G7 single holder owes several") {
            val scenario = secondBand()
                .event("riverside", "Carol", "Grace", "Dave", "Ivan")
                .apply(AddHolding("riverside", "h1", "carol", pln("1200"), CONTRACT))
            val s = scenario.settle("riverside")
            s.targets.values.forAll { it shouldBe pln("300") }
            s.suggestions shouldBe
                // Equal amounts: in order of their ids, not the roster's.
                listOf("dave", "grace", "ivan").map { SuggestedTransfer("carol", it, pln("300")) }
            s.status shouldBe EventStatus.OPEN
            scenario.apply(SettleSuggestions("riverside")).settle("riverside").status shouldBe EventStatus.SETTLED
        }

        // G8: leftover grosz goes to the first party by id (here also the roster's order).
        expect("G8 rounding") {
            val s = Scenario("A", "B", "C").event("e", "A", "B", "C")
                .apply(AddHolding("e", "h", "a", pln("100"), DOOR))
                .settle("e")
            s.lines.map { it.target } shouldBe listOf(pln("33.34"), pln("33.33"), pln("33.33"))
        }

        // G9: unequal weights; the leftover grosz goes to the largest remainder (B and C tie, B's id is first).
        expect("G9 unequal weights") {
            val s = Scenario("A", "B", "C")
                .apply(CreateEvent("e", "e", DATE, participants = listOf(Participant("a", 150), Participant("b"), Participant("c"))))
                .apply(AddHolding("e", "h", "a", pln("1000"), DOOR))
                .settle("e")
            s.lines.map { it.target } shouldBe listOf(pln("428.57"), pln("285.72"), pln("285.71"))
        }

        // G10: a loss blocks settlement; deferring part of a refund fixes it.
        expect("G10 loss and deferral") {
            val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
                .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
                .apply(AddCost("c", "bob", pln("150"), DATE, incurredForEventId = "e"))
            val loss = scenario.settle("e")
            loss.status shouldBe EventStatus.LOSS
            loss.problems shouldBe setOf(SettlementProblem.LOSS)
            loss.canSettle shouldBe false
            scenario.rejected(SettleSuggestions("e")).code shouldBe RejectionCode.CONFLICT

            scenario.apply(SetRefund("e", "c", pln("100")))
            val fixed = scenario.settle("e")
            fixed.net shouldBe pln("0")
            fixed.canSettle shouldBe true
            CostLedger.outstanding(scenario.band, scenario.band.cost("c")!!) shouldBe pln("50")
        }

        // G11: manual hand-over between holders, then suggestions recompute.
        expect("G11 hand over then recompute") {
            val scenario = g2().apply(RecordTransfer("millbrook", "t1", "carol", "alice", pln("500"), "consolidation"))
            val s = scenario.settle("millbrook")
            s.line("alice")!!.delta shouldBe pln("500")
            s.line("carol")!!.delta shouldBe pln("-500")
            s.suggestions.size shouldBe 2

            scenario.apply(SettleSuggestions("millbrook"))
            val settled = scenario.settle("millbrook")
            settled.status shouldBe EventStatus.SETTLED
            settled.lines.sumOf { it.balance.minor } shouldBe settled.income.minor
        }

        // G12: a late edit reopens a settled event with compensating suggestions only.
        expect("G12 late edit reopens") {
            val scenario = g1().apply(SettleSuggestions("oakfield"))
            val recorded = scenario.band.event("oakfield")!!.transfers

            // Money has moved: the cost only remembers the gig and the gig stays settled...
            scenario.apply(AddCost("c-bob", "bob", pln("50"), DATE, incurredForEventId = "oakfield"))
            scenario.settle("oakfield").status shouldBe EventStatus.SETTLED
            // ... until a lead includes the refund, which reopens it.
            scenario.apply(SetRefund("oakfield", "c-bob", pln("50")))
            val s = scenario.settle("oakfield")
            s.status shouldBe EventStatus.OPEN
            scenario.band.event("oakfield")!!.transfers shouldBe recorded
            s.suggestions.toSet() shouldBe
                setOf("alice", "carol", "dave", "frank").map { SuggestedTransfer(it, "bob", pln("10")) }.toSet()
        }

        // The members' stored order moves no grosz and no suggested transfer: ties go by party id.
        expect("roster order changes nothing") {
            val scenario = Scenario("A", "B", "C", "D")
                .event("e", "A", "B", "C", "D")
                .apply(AddHolding("e", "h", "a", pln("100.01"), DOOR))
            val before = scenario.settle("e")
            before.lines.map { it.target } shouldBe listOf(pln("25.01"), pln("25"), pln("25"), pln("25"))
            scenario.storeMembersInOrder("d", "b", "c", "a")
            val after = scenario.settle("e")
            after.lines.associate { it.partyId to it.target } shouldBe before.lines.associate { it.partyId to it.target }
            after.suggestions shouldBe before.suggestions
        }
    })
