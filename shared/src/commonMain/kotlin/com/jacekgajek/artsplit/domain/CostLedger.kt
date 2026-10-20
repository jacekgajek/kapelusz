package com.jacekgajek.artsplit.domain

import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Cost
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.sumOfMoney

enum class CostStatus { PENDING, PARTLY_REFUNDED, REFUNDED }

/** Refund bookkeeping of costs across events and the band fund. */
object CostLedger {

    /** Total refunded so far: event refunds plus fund entries linked to the cost. */
    fun refunded(band: Band, costId: Id): Money =
        band.events.sumOfMoney { event -> event.refundOf(costId)?.amount ?: Money.ZERO } +
            band.fundEntries.filter { it.costId == costId }.sumOfMoney { -it.amount }

    fun outstanding(band: Band, cost: Cost): Money = cost.amount - refunded(band, cost.id)

    fun status(band: Band, cost: Cost): CostStatus {
        val refunded = refunded(band, cost.id)
        return when {
            refunded.isZero -> CostStatus.PENDING
            refunded >= cost.amount -> CostStatus.REFUNDED
            else -> CostStatus.PARTLY_REFUNDED
        }
    }

    /** Costs may only be refunded at events their owner takes part in. */
    fun isRefundableAt(cost: Cost, event: Event): Boolean = event.participant(cost.memberId) != null

    /** Largest refund the event may give for the cost, counting refunds everywhere except this event. */
    fun maxRefundAt(band: Band, cost: Cost, event: Event): Money =
        outstanding(band, cost) + (event.refundOf(cost.id)?.amount ?: Money.ZERO)

    /**
     * Outstanding costs of this event's participants that are not refunded here yet, oldest first:
     * the "Pending from earlier" list of the event's Refunds section.
     */
    fun pendingFromEarlier(band: Band, event: Event): List<Cost> =
        band.costs
            .filter { isRefundableAt(it, event) && event.refundOf(it.id) == null && outstanding(band, it).isPositive }
            .sortedWith(compareBy<Cost> { it.date }.thenBy { it.createdAt })

    /**
     * A member may change their own cost only while no part of it is final: no refund in an event with
     * recorded transfers and no refund from the band fund. Leads are not bound by this.
     */
    fun isLockedForOwner(band: Band, cost: Cost): Boolean =
        band.events.any { it.refundOf(cost.id) != null && it.transfers.isNotEmpty() } ||
            band.fundEntries.any { it.costId == cost.id }
}
