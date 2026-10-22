package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.sumOfMoney

enum class EventStatus { DRAFT, LOSS, OPEN, SETTLED }

enum class SettlementProblem {
    /** Income does not cover the fund amount, refunds and fixed shares. */
    LOSS,

    /** There is money to share but every participant without a fixed share has weight 0. */
    NO_SHARES,

    /** A fund amount is set but nobody holds the band fund. */
    NO_TREASURER,
}

/**
 * One party's numbers in an event. [target] is what the party should end up holding. [isFixed]: the [share] is the
 * participant's fixed amount, not a share by [weight] (ITERATION_3 §2.4).
 */
data class PartyLine(
    val partyId: Id,
    val weight: Int,
    val collected: Money,
    val sent: Money,
    val received: Money,
    val share: Money,
    val refunds: Money,
    val target: Money,
    val isFixed: Boolean = false,
) {
    val balance: Money get() = collected - sent + received

    /** Positive: holds too much and must pay out. Negative: is still owed money. */
    val delta: Money get() = balance - target

    val isSettled: Boolean get() = delta.isZero
}

data class SuggestedTransfer(val from: Id, val to: Id, val amount: Money)

data class Settlement(
    val income: Money,
    val fundAmount: Money,
    val refundsTotal: Money,
    /** Income left to share after the fund amount and refunds; fixed shares come out of it first. */
    val net: Money,
    /** Sum of the participants' fixed shares, paid out of [net] before the rest is split by weight. */
    val fixedTotal: Money,
    /** Participants in party order, followed by [BAND_FUND] when the event involves the fund. */
    val lines: List<PartyLine>,
    val suggestions: List<SuggestedTransfer>,
    val status: EventStatus,
    val problems: Set<SettlementProblem>,
) {
    val canSettle: Boolean get() = status == EventStatus.OPEN && problems.isEmpty()

    /**
     * Only events with nothing left to settle can be archived: settled ones, and drafts with no money in them at all. A
     * refund in a draft would count its cost as covered while nobody pays it; a fund amount would be owed to the fund.
     */
    val canArchive: Boolean
        get() = status == EventStatus.SETTLED || (status == EventStatus.DRAFT && refundsTotal.isZero && fundAmount.isZero)

    /** What is left to split by weight after the fixed shares; negative on a [SettlementProblem.LOSS]. */
    val rest: Money get() = net - fixedTotal

    /** How much income is missing on a [SettlementProblem.LOSS], or zero. */
    val shortfall: Money get() = if (rest.isNegative) -rest else Money.ZERO
    fun line(partyId: Id): PartyLine? = lines.firstOrNull { it.partyId == partyId }
    fun suggestionsFor(partyId: Id) = suggestions.filter { it.from == partyId || it.to == partyId }
}

object SettlementCalculator {

    fun settle(band: Band, event: Event): Settlement {
        val order = partyOrder(band, event)
        val income = event.income
        val refundsTotal = event.refunds.sumOfMoney { it.amount }
        val net = income - event.fundAmount - refundsTotal
        // Fixed shares come off the top; the rest is split by weight among the others (ITERATION_3 §2.4).
        val fixedTotal = event.participants.sumOfMoney { it.fixedShare ?: Money.ZERO }
        val rest = net - fixedTotal
        val weighted = order.mapNotNull { partyId -> event.participant(partyId)!!.takeIf { it.fixedShare == null } }
        val totalWeight = weighted.sumOf { it.weight.toLong() }

        val problems = buildSet {
            if (rest.isNegative) add(SettlementProblem.LOSS)
            if (rest.isPositive && totalWeight == 0L) add(SettlementProblem.NO_SHARES)
            // Only while money for the fund is still to move: a settled event stays settled after the fund is turned off.
            if (band.treasurerMemberId == null && !BandFund.isHandedOver(event)) add(SettlementProblem.NO_TREASURER)
        }

        // A fixed share is owed even at a loss, like a refund: the targets show what the income would have to cover.
        val fixedShares = event.participants.mapNotNull { p -> p.fixedShare?.let { p.partyId to it } }.toMap()
        val shares = fixedShares + if (rest.isPositive && totalWeight > 0) splitByWeight(rest, weighted, totalWeight) else emptyMap()
        val refundsByParty = event.refunds.groupBy { band.cost(it.costId)?.memberId }
            .mapValues { (_, refunds) -> refunds.sumOfMoney { it.amount } }

        val involvesFund = !event.fundAmount.isZero ||
            event.transfers.any { it.from == BAND_FUND || it.to == BAND_FUND }
        val partyIds = order + if (involvesFund) listOf(BAND_FUND) else emptyList()

        val lines = partyIds.map { partyId ->
            val share = shares[partyId] ?: Money.ZERO
            val refunds = refundsByParty[partyId] ?: Money.ZERO
            PartyLine(
                partyId = partyId,
                weight = event.participant(partyId)?.weight ?: 0,
                collected = event.holdings.filter { it.partyId == partyId }.sumOfMoney { it.amount },
                sent = event.transfers.filter { it.from == partyId }.sumOfMoney { it.amount },
                received = event.transfers.filter { it.to == partyId }.sumOfMoney { it.amount },
                share = share,
                refunds = refunds,
                target = if (partyId == BAND_FUND) event.fundAmount else share + refunds,
                isFixed = partyId in fixedShares,
            )
        }

        val status = when {
            // No money in it yet (ITERATION_3 R10): an event the band fund pays for is not a draft, even with nothing collected.
            event.holdings.isEmpty() && event.transfers.isEmpty() && !event.fundAmount.isNegative -> EventStatus.DRAFT
            SettlementProblem.LOSS in problems -> EventStatus.LOSS
            problems.isEmpty() && lines.all { it.isSettled } -> EventStatus.SETTLED
            else -> EventStatus.OPEN
        }
        val suggestions = if (status == EventStatus.OPEN && problems.isEmpty()) {
            suggestTransfers(lines, band.treasurerMemberId)
        } else {
            emptyList()
        }
        return Settlement(income, event.fundAmount, refundsTotal, net, fixedTotal, lines, suggestions, status, problems)
    }

    /** Roster members in roster order, then guests in the order they were added. */
    fun partyOrder(band: Band, event: Event): List<Id> {
        val memberOrder = band.members.withIndex().associate { (index, member) -> member.id to index }
        val guestOrder = event.guests.withIndex().associate { (index, guest) -> guest.id to index }
        return event.participants
            .sortedWith(
                compareBy<Participant> { memberOrder[it.partyId] ?: Int.MAX_VALUE }
                    .thenBy { guestOrder[it.partyId] ?: Int.MAX_VALUE },
            )
            .map { it.partyId }
    }

    /**
     * Splits [net] proportionally to the weights of [participants] (in party order). Each share is rounded down; the
     * leftover minor units go one by one to the largest fractional remainders, ties broken by party order. The shares
     * always sum to exactly [net].
     */
    private fun splitByWeight(net: Money, participants: List<Participant>, totalWeight: Long): Map<Id, Money> {
        class Part(val partyId: Id, val index: Int, val base: Long, val remainder: Long)

        // net × weight ÷ total, computed without forming net × weight (ITERATION_3 R1): exact, and no overflow even for
        // amounts stored before the limits. With net = q·total + r: net·w / total = q·w + r·w / total.
        val q = net.minor / totalWeight
        val r = net.minor % totalWeight
        val parts = participants.mapIndexed { index, participant ->
            val weight = participant.weight.toLong()
            Part(participant.partyId, index, q * weight + r * weight / totalWeight, r * weight % totalWeight)
        }
        val leftover = net.minor - parts.sumOf { it.base }
        val bonus = parts
            .sortedWith(compareByDescending<Part> { it.remainder }.thenBy { it.index })
            .take(leftover.toInt())
            .map { it.partyId }
            .toSet()
        return parts.associate { it.partyId to Money(it.base + if (it.partyId in bonus) 1 else 0) }
    }

    /**
     * Greedy matching of the largest surplus with the largest deficit (ties by party order), which needs at most
     * n−1 transfers. The treasurer and the fund are paired first, so the treasurer simply keeps the fund's money.
     */
    private fun suggestTransfers(lines: List<PartyLine>, treasurerId: Id?): List<SuggestedTransfer> {
        val index = lines.withIndex().associate { (i, line) -> line.partyId to i }
        val delta = lines.associate { it.partyId to it.delta.minor }.toMutableMap()
        val result = mutableListOf<SuggestedTransfer>()

        fun move(from: Id, to: Id, amount: Long) {
            if (amount <= 0) return
            result += SuggestedTransfer(from, to, Money(amount))
            delta[from] = delta.getValue(from) - amount
            delta[to] = delta.getValue(to) + amount
        }

        if (treasurerId != null && treasurerId in delta && BAND_FUND in delta) {
            val treasurer = delta.getValue(treasurerId)
            val fund = delta.getValue(BAND_FUND)
            if (treasurer > 0 && fund < 0) move(treasurerId, BAND_FUND, minOf(treasurer, -fund))
            if (fund > 0 && treasurer < 0) move(BAND_FUND, treasurerId, minOf(fund, -treasurer))
        }

        while (true) {
            val debtor = delta.filterValues { it > 0 }.keys
                .minWithOrNull(compareByDescending<Id> { delta.getValue(it) }.thenBy { index.getValue(it) }) ?: break
            val creditor = delta.filterValues { it < 0 }.keys
                .minWithOrNull(compareBy<Id> { delta.getValue(it) }.thenBy { index.getValue(it) }) ?: break
            move(debtor, creditor, minOf(delta.getValue(debtor), -delta.getValue(creditor)))
        }
        return result
    }
}
