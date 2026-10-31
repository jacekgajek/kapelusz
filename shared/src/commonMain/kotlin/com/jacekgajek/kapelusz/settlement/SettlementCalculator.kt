package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.SharePercent
import com.jacekgajek.kapelusz.model.sumOfMoney
import com.jacekgajek.kapelusz.text.sortedByName

/**
 * [AWAITING]: everything that came in has been passed on, but the organiser still owes part of the income
 * (ITERATION_4 §2.2).
 */
enum class EventStatus { DRAFT, LOSS, OPEN, AWAITING, SETTLED }

enum class SettlementProblem {
    /** Income does not cover the fund amount, refunds and fixed shares. */
    LOSS,

    /**
     * There is money to share but nobody to share it: every participant by weight has weight 0, or there is none and
     * the percentages (ITERATION_7 §6) do not add up to 100%.
     */
    NO_SHARES,

    /** The percentages of the profit, the band fund's included, add up to more than 100% (ITERATION_7 §6). */
    OVER_PERCENT,

    /** A fund amount is set but nobody holds the band fund. */
    NO_TREASURER,
}

/**
 * One party's numbers in an event. [target] is what the party should end up holding. [isFixed]: the [share] is the
 * participant's fixed amount, not a share by [weight] (ITERATION_3 §2.4); [percent]: their percentage of the profit
 * (ITERATION_7 §6). [partnerId]: a pair settling together (ITERATION_5 §7), [partyId] and [partnerId] as one, every
 * number the sum of theirs.
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
    val partnerId: Id? = null,
    val percent: Int? = null,
) {
    /** The people in this party: one, or the two of a pair. */
    val partyIds: List<Id> get() = listOfNotNull(partyId, partnerId)

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
    /** Sum of the participants' percentages of the profit (ITERATION_7 §6), also paid out of [net] first. */
    val percentTotal: Money,
    /** Participants in party order, followed by [BAND_FUND] when the event involves the fund; each on their own. */
    val lines: List<PartyLine>,
    /** Between [parties]: a pair is named by its [PartyLine.partyId]. */
    val suggestions: List<SuggestedTransfer>,
    val status: EventStatus,
    val problems: Set<SettlementProblem>,
    /** The pairs settling together here (ITERATION_5 §7), each partner mapped to the other. */
    val partners: Map<Id, Id> = emptyMap(),
) {
    /**
     * Who settles here: [lines] with each pair as one line (ITERATION_5 §7), in the place of the partner first in party
     * order. The same as [lines] without pairs.
     */
    val parties: List<PartyLine> by lazy { mergePairs(lines, partners) }

    val canSettle: Boolean get() = status == EventStatus.OPEN && problems.isEmpty()

    /**
     * Only events with nothing left to settle can be archived: settled ones, and drafts with no money in them at all. A
     * refund in a draft would count its cost as covered while nobody pays it; a fund amount would be owed to the fund.
     */
    val canArchive: Boolean
        get() = status == EventStatus.SETTLED || (status == EventStatus.DRAFT && refundsTotal.isZero && fundAmount.isZero)

    /** What is left to split by weight after the fixed shares and percentages; negative on a [SettlementProblem.LOSS]. */
    val rest: Money get() = net - fixedTotal - percentTotal

    /** How much income is missing on a [SettlementProblem.LOSS], or zero. */
    val shortfall: Money get() = if (rest.isNegative) -rest else Money.ZERO
    fun line(partyId: Id): PartyLine? = lines.firstOrNull { it.partyId == partyId }

    /** The party [partyId] settles in: their own line, or their pair's. */
    fun party(partyId: Id): PartyLine? = parties.firstOrNull { partyId in it.partyIds }

    /** Suggestions [partyId] is in, also as half of a pair. */
    fun suggestionsFor(partyId: Id): List<SuggestedTransfer> {
        val ids = party(partyId)?.partyIds ?: listOf(partyId)
        return suggestions.filter { it.from in ids || it.to in ids }
    }
}

object SettlementCalculator {

    fun settle(band: Band, event: Event): Settlement {
        val order = partyOrder(band, event)
        val income = event.income
        val refundsTotal = event.refunds.sumOfMoney { it.amount }
        val fund = event.fund
        val net = income - fund - refundsTotal
        val participants = order.map { event.participant(it)!! }
        // Percentages are of the profit, plus what the band fund adds (ITERATION_7 §6): all of the same base, so they
        // add up with the fund's own percentage.
        val percentBase = income - refundsTotal - if (fund.isNegative) fund else Money.ZERO
        val percentShares = participants.mapNotNull { p -> p.percent?.let { p.partyId to SharePercent.of(it, percentBase) } }.toMap()
        val percentTotal = percentShares.values.sumOfMoney { it }
        val percentSum = participants.sumOf { it.percent ?: 0 } + (event.fundPercent ?: 0)
        // Fixed shares and percentages come off the top; the rest is split by weight among the others (ITERATION_3 §2.4).
        val fixedTotal = event.participants.sumOfMoney { it.fixedShare ?: Money.ZERO }
        val rest = net - fixedTotal - percentTotal
        val weighted = participants.filter { it.byWeight }
        val totalWeight = weighted.sumOf { it.weight.toLong() }
        // Everyone on a percentage and 100% in all: what rounding left goes to them, by their percentages.
        val byPercent = participants.filter { it.percent != null }.takeIf { totalWeight == 0L && percentSum == SharePercent.MAX }.orEmpty()

        val problems = buildSet {
            if (percentSum > SharePercent.MAX) add(SettlementProblem.OVER_PERCENT)
            // Over 100% short of money only because of the percentages is not a loss; short without them, it still is.
            if (rest.isNegative && (percentSum <= SharePercent.MAX || (net - fixedTotal).isNegative)) add(SettlementProblem.LOSS)
            if (rest.isPositive && totalWeight == 0L && byPercent.isEmpty()) add(SettlementProblem.NO_SHARES)
            // Only while money for the fund is still to move: a settled event stays settled after the fund is turned off.
            if (band.treasurerMemberId == null && !BandFund.isHandedOver(event)) add(SettlementProblem.NO_TREASURER)
        }

        // A fixed share is owed even at a loss, like a refund: the targets show what the income would have to cover.
        val fixedShares = event.participants.mapNotNull { p -> p.fixedShare?.let { p.partyId to it } }.toMap()
        val restShares = when {
            !rest.isPositive -> emptyMap()
            totalWeight > 0 -> splitByWeight(rest, weighted, totalWeight)
            byPercent.isNotEmpty() -> splitByWeight(rest, byPercent.map { it.copy(weight = it.percent!!) }, byPercent.sumOf { it.percent!!.toLong() })
            else -> emptyMap()
        }
        val shares = (fixedShares.keys + percentShares.keys + restShares.keys).associateWith { id ->
            (fixedShares[id] ?: Money.ZERO) + (percentShares[id] ?: Money.ZERO) + (restShares[id] ?: Money.ZERO)
        }
        val refundsByParty = event.refunds.groupBy { band.cost(it.costId)?.memberId }
            .mapValues { (_, refunds) -> refunds.sumOfMoney { it.amount } }

        val involvesFund = !fund.isZero ||
            event.transfers.any { it.from == BAND_FUND || it.to == BAND_FUND } || event.holdings.any { it.partyId == BAND_FUND }
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
                target = if (partyId == BAND_FUND) fund else share + refunds,
                isFixed = partyId in fixedShares,
                percent = event.participant(partyId)?.percent,
            )
        }

        // A pair settles as one (ITERATION_5 §7): what one of them holds may pay what the other is owed.
        val partners = SettleTogether.partners(band, event)
        val parties = mergePairs(lines, partners)
        val status = when {
            // No money in it yet (ITERATION_3 R10): an event the band fund pays for is not a draft, even with nothing collected.
            event.holdings.isEmpty() && event.transfers.isEmpty() && !fund.isNegative -> EventStatus.DRAFT

            SettlementProblem.LOSS in problems -> EventStatus.LOSS

            problems.isEmpty() && parties.all { it.isSettled } -> EventStatus.SETTLED

            // The organiser's fee holds no line: nobody has a surplus to pass on until it comes (ITERATION_4 §2.2).
            problems.isEmpty() && event.awaited.isPositive && parties.none { it.delta.isPositive } -> EventStatus.AWAITING

            else -> EventStatus.OPEN
        }
        val suggestions = if (status == EventStatus.OPEN && problems.isEmpty()) {
            // The treasurer's pair is paired with the fund first, as the treasurer alone would be.
            val treasurer = band.treasurerMemberId?.let { id -> parties.firstOrNull { id in it.partyIds }?.partyId ?: id }
            suggestTransfers(parties, treasurer)
        } else {
            emptyList()
        }
        return Settlement(income, fund, refundsTotal, net, fixedTotal, percentTotal, lines, suggestions, status, problems, partners)
    }

    /** Members, then guests, each in alphabetical order (ITERATION_8 §2). */
    fun partyOrder(band: Band, event: Event): List<Id> {
        val (members, others) = event.participants.map { it.partyId }.partition { band.member(it) != null }
        return members.sortedByName { band.member(it)!!.name } + others.sortedByName { event.guest(it)?.name ?: it }
    }

    /**
     * Splits [net] proportionally to the weights of [participants]. Each share is rounded down; the leftover minor
     * units go one by one to the largest fractional remainders, ties broken by party id: never by the roster's order,
     * which leaders change at will, so moving a member never moves a grosz in a settled event.
     */
    private fun splitByWeight(net: Money, participants: List<Participant>, totalWeight: Long): Map<Id, Money> {
        class Part(val partyId: Id, val base: Long, val remainder: Long)

        // net × weight ÷ total, computed without forming net × weight (ITERATION_3 R1): exact, and no overflow even for
        // amounts stored before the limits. With net = q·total + r: net·w / total = q·w + r·w / total.
        val q = net.minor / totalWeight
        val r = net.minor % totalWeight
        val parts = participants.map { participant ->
            val weight = participant.weight.toLong()
            Part(participant.partyId, q * weight + r * weight / totalWeight, r * weight % totalWeight)
        }
        val leftover = net.minor - parts.sumOf { it.base }
        val bonus = parts
            .sortedWith(compareByDescending<Part> { it.remainder }.thenBy { it.partyId })
            .take(leftover.toInt())
            .map { it.partyId }
            .toSet()
        return parts.associate { it.partyId to Money(it.base + if (it.partyId in bonus) 1 else 0) }
    }

    /**
     * Greedy matching of the largest surplus with the largest deficit, which needs at most n−1 transfers. Ties go by
     * party id (a pair's smaller one), not by the roster's order, so reordering the roster doesn't change them. The
     * treasurer and the fund are paired first, so the treasurer simply keeps the fund's money.
     */
    private fun suggestTransfers(lines: List<PartyLine>, treasurerId: Id?): List<SuggestedTransfer> {
        val key = lines.associate { line -> line.partyId to line.partyIds.min() }
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
                .minWithOrNull(compareByDescending<Id> { delta.getValue(it) }.thenBy { key.getValue(it) }) ?: break
            val creditor = delta.filterValues { it < 0 }.keys
                .minWithOrNull(compareBy<Id> { delta.getValue(it) }.thenBy { key.getValue(it) }) ?: break
            move(debtor, creditor, minOf(delta.getValue(debtor), -delta.getValue(creditor)))
        }
        return result
    }
}

/**
 * [lines] with each pair of [partners] summed into one line, where the partner first in [lines] is, named by the
 * smaller of the two ids: not by the roster's order, so reordering the roster doesn't change the suggestions.
 */
internal fun mergePairs(lines: List<PartyLine>, partners: Map<Id, Id>): List<PartyLine> {
    if (partners.isEmpty()) return lines
    val byId = lines.associateBy { it.partyId }
    val seen = mutableSetOf<Id>()
    return lines.mapNotNull { line ->
        if (line.partyId in seen) return@mapNotNull null
        val partner = partners[line.partyId]?.let { byId[it] } ?: return@mapNotNull line
        seen += partner.partyId
        val (first, second) = listOf(line.partyId, partner.partyId).sorted()
        PartyLine(
            partyId = first,
            weight = line.weight + partner.weight,
            collected = line.collected + partner.collected,
            sent = line.sent + partner.sent,
            received = line.received + partner.received,
            share = line.share + partner.share,
            refunds = line.refunds + partner.refunds,
            target = line.target + partner.target,
            partnerId = second,
        )
    }
}
