package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.sum
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.targets
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** Randomized checks of settlement invariants over many generated events. */
class InvariantTest :
    ExpectSpec({

        expect("settlement invariants hold") {
            val random = Random(20261020)
            val settled = (0 until 300).count { round -> checkRandomEvent(random, round) }
            withClue("too few generated events were settleable ($settled)") { settled shouldBeGreaterThan 100 }
        }
    })

/** Returns whether the generated event was settleable (and thus fully checked). */
private fun checkRandomEvent(random: Random, round: Int): Boolean {
    val names = List(random.nextInt(2, 9)) { "M$it" }
    val scenario = Scenario(*names.toTypedArray()).apply(SetTreasurer("m0"))
    val participants = names.map { it.lowercase() }.filter { random.nextInt(4) > 0 }.ifEmpty { listOf("m0") }
    scenario.apply(
        CreateEvent("e", "e", DATE, participants = participants.map { Participant(it, random.nextInt(0, 4) * 50) }),
    )
    repeat(random.nextInt(1, 5)) { i ->
        val holder = participants.random(random)
        scenario.apply(AddHolding("e", "h$i", holder, Money(random.nextLong(1, 500_000)), IncomeSource.entries.random(random)))
    }
    repeat(random.nextInt(0, 4)) { i ->
        scenario.apply(AddCost("c$i", participants.random(random), Money(random.nextLong(1, 100_000)), DATE, incurredForEventId = "e"))
    }
    if (random.nextBoolean()) scenario.apply(SetFundAmount("e", Money(random.nextLong(-20_000, 50_000))))
    if (random.nextBoolean() && participants.size > 1) {
        scenario.apply(RecordTransfer("e", "manual", participants[0], participants[1], Money(random.nextLong(1, 10_000))))
    }

    val s = SettlementCalculator.settle(scenario.band, scenario.band.event("e")!!)
    val context = "round $round: $s"
    withClue("transfers are zero-sum; $context") { s.lines.sumOf { it.balance.minor } shouldBe s.income.minor }
    if (s.net.isNegative) {
        withClue(context) { s.status shouldBe EventStatus.LOSS }
        return false
    }
    if (!s.canSettle) return false

    withClue("targets add up to income; $context") { s.lines.sumOf { it.target.minor } shouldBe s.income.minor }
    val shared = s.lines.filter { it.partyId != BAND_FUND }.sumOf { it.share.minor }
    withClue("shares add up to net; $context") { shared shouldBe s.net.minor }
    withClue("at most n-1 transfers; $context") { s.suggestions.size shouldBeLessThanOrEqual s.lines.size - 1 }
    withClue(context) { s.suggestions.all { it.amount.isPositive } shouldBe true }

    scenario.apply(SettleSuggestions("e"))
    withClue(context) {
        SettlementCalculator.settle(scenario.band, scenario.band.event("e")!!).status shouldBe EventStatus.SETTLED
    }
    return true
}
