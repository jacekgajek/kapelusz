package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.domain.CostStatuses
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sumOfMoney
import kotlinx.coroutines.yield
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The period a summary covers (ITERATION_6 §7): presets move with time, a custom range is fixed. */
@Serializable
sealed interface SummaryPeriod {
    /** First and last day, both included; null: no limit. */
    fun range(today: LocalDate): Pair<LocalDate?, LocalDate?>

    @Serializable @SerialName("last12")
    data object Last12Months : SummaryPeriod {
        override fun range(today: LocalDate) = today.minus(DatePeriod(years = 1)).plus(DatePeriod(days = 1)) to today
    }

    @Serializable @SerialName("thisYear")
    data object ThisYear : SummaryPeriod {
        override fun range(today: LocalDate) = LocalDate(today.year, 1, 1) to today
    }

    @Serializable @SerialName("lastYear")
    data object LastYear : SummaryPeriod {
        override fun range(today: LocalDate) = LocalDate(today.year - 1, 1, 1) to LocalDate(today.year - 1, 12, 31)
    }

    @Serializable @SerialName("all")
    data object AllTime : SummaryPeriod {
        override fun range(today: LocalDate) = null to null
    }

    @Serializable @SerialName("custom")
    data class Custom(val from: LocalDate, val to: LocalDate) : SummaryPeriod {
        override fun range(today: LocalDate) = from to to
    }
}

/** What one person got in the period: their shares, and payments of their costs. */
data class PersonTotals(val earned: Money, val refunded: Money) {
    val isZero: Boolean get() = earned.isZero && refunded.isZero

    operator fun plus(other: PersonTotals) = PersonTotals(earned + other.earned, refunded + other.refunded)

    companion object {
        val ZERO = PersonTotals(Money.ZERO, Money.ZERO)
    }
}

/** Money into and out of the band fund in the period, and what it held at the period's end. */
data class FundTotals(val into: Money, val out: Money, val balance: Money)

data class PeriodSummary(
    /** Settled events in the period: only their money counts (it is final). */
    val events: Int,
    val collected: Money,
    /** Everyone's shares. */
    val paidOut: Money,
    /** Costs fully refunded in the period. */
    val costs: Money,
    /** Null when the band has no fund. */
    val fund: FundTotals?,
    /** Fully refunded costs per category, largest first. */
    val categories: List<Pair<CostCategory, Money>>,
    /** Members with a number in the period, by what they earned, highest first. */
    val members: List<Pair<Member, PersonTotals>>,
    /** All guests together; null when they got nothing. */
    val guests: PersonTotals?,
    val me: PersonTotals,
    /** The user's costs not fully refunded yet, as of today, whatever the period. */
    val myWaiting: Money,
    /** Events in the period with money, not settled yet: not counted. */
    val unsettled: List<Event>,
) {
    /** Nothing settled in the period. */
    val isEmpty: Boolean get() = events == 0 && costs.isZero && fund.let { it == null || (it.into.isZero && it.out.isZero) } && members.isEmpty() && guests == null
}

/**
 * The summary of [band] from [from] to [to] (both included; null: no limit), as ITERATION_6 §7 counts it: settled
 * events' money on the event's date, fund entries on their own, each refund payment on its date, costs once fully
 * refunded on the date that happened. Settles every event, the costly part: yields between events, to be cancelled
 * when outdated and to let the web's one thread draw.
 */
suspend fun periodSummary(band: Band, me: Member?, from: LocalDate?, to: LocalDate?): PeriodSummary {
    fun inPeriod(date: LocalDate) = (from == null || date >= from) && (to == null || date <= to)

    val settlements = band.events.associate { event ->
        yield()
        event.id to SettlementCalculator.settle(band, event)
    }
    val settled = band.events.filter { settlements.getValue(it.id).status == EventStatus.SETTLED }
    val settledInPeriod = settled.filter { inPeriod(it.date) }

    val totals = mutableMapOf<Id, PersonTotals>()
    fun add(partyId: Id, person: PersonTotals) {
        totals[partyId] = (totals[partyId] ?: PersonTotals.ZERO) + person
    }
    var guests = PersonTotals.ZERO
    for (event in settledInPeriod) {
        for (line in settlements.getValue(event.id).lines) {
            if (line.partyId == BAND_FUND) continue
            val person = PersonTotals(line.share, line.refunds)
            if (event.guest(line.partyId) != null) guests += person else add(line.partyId, person)
        }
    }
    // Refunds from the fund, paid on the entry's date.
    for (entry in band.fundEntries) {
        val cost = entry.costId?.let(band::cost) ?: continue
        if (inPeriod(entry.date)) add(cost.memberId, PersonTotals(Money.ZERO, -entry.amount))
    }

    // A cost counts once fully refunded, on the latest date among its refunds (settled events and fund entries).
    val statuses = CostStatuses(band)
    val refundedCosts = band.costs.filter { statuses.status(it) == CostStatus.REFUNDED }.mapNotNull { cost ->
        val dates = settled.filter { it.refundOf(cost.id) != null }.map { it.date } +
            band.fundEntries.filter { it.costId == cost.id }.map { it.date }
        dates.maxOrNull()?.takeIf(::inPeriod)?.let { cost }
    }

    val fund = band.treasurerMemberId?.let {
        val eventFlows = settled.map { it.date to it.fundAmount }
        val entryFlows = band.fundEntries.map { it.date to it.amount }
        val flows = eventFlows + entryFlows
        val inside = flows.filter { (date, _) -> inPeriod(date) }.map { it.second }
        FundTotals(
            into = inside.filter { it.isPositive }.sumOfMoney { it },
            out = -inside.filter { it.isNegative }.sumOfMoney { it },
            balance = flows.filter { (date, _) -> to == null || date <= to }.sumOfMoney { it.second },
        )
    }

    val members = totals.mapNotNull { (id, person) -> band.member(id)?.takeIf { !person.isZero }?.let { it to person } }
        .sortedWith(compareByDescending<Pair<Member, PersonTotals>> { it.second.earned }.thenBy { it.first.name })
    return PeriodSummary(
        events = settledInPeriod.size,
        collected = settledInPeriod.sumOfMoney { it.income },
        paidOut = settledInPeriod.sumOfMoney { event -> settlements.getValue(event.id).lines.filter { it.partyId != BAND_FUND }.sumOfMoney { it.share } },
        costs = refundedCosts.sumOfMoney { it.amount },
        fund = fund,
        categories = refundedCosts.groupBy { it.category ?: CostCategory.OTHER }
            .map { (category, costs) -> category to costs.sumOfMoney { it.amount } }
            .sortedByDescending { it.second },
        members = members,
        guests = guests.takeIf { !it.isZero },
        me = me?.let { totals[it.id] } ?: PersonTotals.ZERO,
        myWaiting = me?.let { band.costs.filter { cost -> cost.memberId == it.id }.sumOfMoney(statuses::unpaid) } ?: Money.ZERO,
        // Drafts have no money in them yet: nothing to count, so not named either.
        unsettled = band.events.filter {
            inPeriod(it.date) && settlements.getValue(it.id).status.let { status -> status != EventStatus.SETTLED && status != EventStatus.DRAFT }
        }.sortedBy { it.date },
    )
}
