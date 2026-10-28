package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.domain.CostLedger
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
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

/** The period [bandId]'s summary was last shown for; at first, or unreadable: the last 12 months. */
fun AppSettings.summaryPeriod(bandId: String): SummaryPeriod =
    summaryPeriods[bandId]?.let { runCatching { KapeluszJson.decodeFromString(SummaryPeriod.serializer(), it) }.getOrNull() }
        ?: SummaryPeriod.Last12Months

fun AppSettings.withSummaryPeriod(bandId: String, period: SummaryPeriod): AppSettings =
    copy(summaryPeriods = summaryPeriods + (bandId to KapeluszJson.encodeToString(SummaryPeriod.serializer(), period)))

/** What one person got in the period: their shares, and payments of their costs. */
data class PersonTotals(val earned: Money, val refunded: Money) {
    val isZero: Boolean get() = earned.isZero && refunded.isZero

    operator fun plus(other: PersonTotals) = PersonTotals(earned + other.earned, refunded + other.refunded)

    companion object {
        val ZERO = PersonTotals(Money.ZERO, Money.ZERO)
    }
}

/**
 * Money into and out of the band fund in the period, and what it held at the period's end; [intoBy] and [outBy] break
 * them down by who paid in or got a cost refunded (ITERATION_7 §2e), largest first.
 */
data class FundTotals(
    val into: Money,
    val out: Money,
    val balance: Money,
    val intoBy: List<Pair<FundSource, Money>> = emptyList(),
    val outBy: List<Pair<FundSource, Money>> = emptyList(),
)

/** Whose money went into or out of the band fund. */
sealed interface FundSource {
    data class Of(val member: Member) : FundSource
    /** Paid in from outside the band. */
    data object Outside : FundSource
    /** An event's part, or what the fund added to one. */
    data object Events : FundSource
}

data class PeriodSummary(
    /** Settled events in the period: only their money counts (it is final). */
    val events: Int,
    val collected: Money,
    /** Everyone's shares. */
    val paidOut: Money,
    /** Cost refunds paid at the settled events. */
    val refundedAtEvents: Money,
    /** The fund's part of the settled events (negative: the fund covered some). With [paidOut] and
     * [refundedAtEvents], all of [collected]. */
    val toFund: Money,
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
 * refunded on the date that happened. [onlyEventsOf]: an artist's id, naming only their unsettled events. Settles every event, the costly part: yields between events, to be cancelled
 * when outdated and to let the web's one thread draw.
 */
suspend fun periodSummary(
    band: Band,
    me: Member?,
    from: LocalDate?,
    to: LocalDate?,
    onlyEventsOf: Id? = null,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): PeriodSummary {
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
    // Paid amounts from the settlements above: settling every event again (as CostStatuses does) costs as much again.
    fun paid(costId: Id) = settled.sumOfMoney { it.refundOf(costId)?.amount ?: Money.ZERO } + CostLedger.fundRefunds(band, costId)
    val refundedCosts = band.costs.mapNotNull { cost ->
        yield()
        if (paid(cost.id) < cost.amount) return@mapNotNull null
        val dates = settled.filter { it.refundOf(cost.id) != null }.map { it.date } +
            band.fundEntries.filter { it.costId == cost.id }.map { it.date }
        dates.maxOrNull()?.takeIf(::inPeriod)?.let { cost }
    }

    // The fund is money actually there, as on the band fund's page. Transfers to or from it count on the day they were
    // recorded (a gig's part may be handed over before the gig), fees paid straight to it on the gig's date, entries on
    // their own.
    val fund = band.treasurerMemberId?.let {
        val flows: List<Triple<LocalDate, Money, FundSource>> = band.events.flatMap { event ->
            val transfers = event.transfers.mapNotNull { transfer ->
                val amount = when (BAND_FUND) {
                    transfer.to -> transfer.amount
                    transfer.from -> -transfer.amount
                    else -> return@mapNotNull null
                }
                val recorded = transfer.recordedAt.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(timeZone).date }
                Triple(recorded ?: event.date, amount, FundSource.Events)
            }
            transfers + event.holdings.filter { it.partyId == BAND_FUND }.map { Triple(event.date, it.amount, FundSource.Events) }
        } + band.fundEntries.map { entry ->
            // Paid in by someone, or a refund of someone's cost.
            val who = (entry.paidBy ?: entry.costId?.let { band.cost(it)?.memberId })?.let { band.member(it) }
            Triple(entry.date, entry.amount, who?.let { FundSource.Of(it) } ?: FundSource.Outside)
        }
        val inside = flows.filter { (date, _, _) -> inPeriod(date) }
        fun by(amounts: List<Triple<LocalDate, Money, FundSource>>) = amounts.groupBy { it.third }
            .map { (source, flows) -> source to flows.sumOfMoney { it.second }.let { if (it.isNegative) -it else it } }
            .filter { !it.second.isZero }.sortedByDescending { it.second }
        FundTotals(
            into = inside.filter { it.second.isPositive }.sumOfMoney { it.second },
            out = -inside.filter { it.second.isNegative }.sumOfMoney { it.second },
            balance = flows.filter { (date, _, _) -> to == null || date <= to }.sumOfMoney { it.second },
            intoBy = by(inside.filter { it.second.isPositive }),
            outBy = by(inside.filter { it.second.isNegative }),
        )
    }

    val members = totals.mapNotNull { (id, person) -> band.member(id)?.takeIf { !person.isZero }?.let { it to person } }
        .sortedWith(compareByDescending<Pair<Member, PersonTotals>> { it.second.earned }.thenBy { it.first.name })
    return PeriodSummary(
        events = settledInPeriod.size,
        collected = settledInPeriod.sumOfMoney { it.income },
        paidOut = settledInPeriod.sumOfMoney { event -> settlements.getValue(event.id).lines.filter { it.partyId != BAND_FUND }.sumOfMoney { it.share } },
        refundedAtEvents = settledInPeriod.sumOfMoney { event -> event.refunds.sumOfMoney { it.amount } },
        toFund = settledInPeriod.sumOfMoney { it.fundAmount },
        costs = refundedCosts.sumOfMoney { it.amount },
        fund = fund,
        categories = refundedCosts.groupBy { it.category ?: CostCategory.OTHER }
            .map { (category, costs) -> category to costs.sumOfMoney { it.amount } }
            .sortedByDescending { it.second },
        members = members,
        guests = guests.takeIf { !it.isZero },
        me = me?.let { totals[it.id] } ?: PersonTotals.ZERO,
        myWaiting = me?.let { band.costs.filter { cost -> cost.memberId == it.id }.sumOfMoney { cost -> cost.amount - paid(cost.id) } } ?: Money.ZERO,
        // Drafts have no money in them yet: nothing to count, so not named either.
        unsettled = band.events.filter {
            inPeriod(it.date) && settlements.getValue(it.id).status.let { status -> status != EventStatus.SETTLED && status != EventStatus.DRAFT } &&
                (onlyEventsOf == null || it.participant(onlyEventsOf) != null)
        }.sortedBy { it.date },
    )
}
