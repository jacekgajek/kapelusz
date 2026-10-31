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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Randomized checks of settlement invariants over many generated events. */
class InvariantTest {

    @Test
    fun settlementInvariantsHold() {
        val random = Random(20261020)
        val settled = (0 until 300).count { round -> checkRandomEvent(random, round) }
        assertTrue(settled > 100, "too few generated events were settleable ($settled)")
    }

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
        assertEquals(s.income.minor, s.lines.sumOf { it.balance.minor }, "transfers are zero-sum; $context")
        if (s.net.isNegative) {
            assertEquals(EventStatus.LOSS, s.status, context)
            return false
        }
        if (!s.canSettle) return false

        assertEquals(s.income.minor, s.lines.sumOf { it.target.minor }, "targets add up to income; $context")
        val shared = s.lines.filter { it.partyId != BAND_FUND }.sumOf { it.share.minor }
        assertEquals(s.net.minor, shared, "shares add up to net; $context")
        assertTrue(s.suggestions.size <= s.lines.size - 1, "at most n-1 transfers; $context")
        assertTrue(s.suggestions.all { it.amount.isPositive }, context)

        scenario.apply(SettleSuggestions("e"))
        assertEquals(EventStatus.SETTLED, SettlementCalculator.settle(scenario.band, scenario.band.event("e")!!).status, context)
        return true
    }
}
