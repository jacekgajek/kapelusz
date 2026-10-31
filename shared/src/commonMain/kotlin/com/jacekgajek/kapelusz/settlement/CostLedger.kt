package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sumOfMoney

/** What the cost's owner sees: nothing assigned, assigned but not paid yet, partly paid, fully paid. */
enum class CostStatus { PENDING, REFUND_PLANNED, PARTLY_REFUNDED, REFUNDED }

/**
 * Refund bookkeeping of costs across events and the band fund.
 *
 * A refund is *assigned* as soon as an event includes it ([refunded], [outstanding]; used to prevent refunding a cost
 * twice) and *paid* once that event is settled ([com.jacekgajek.kapelusz.view.CostStatuses.paid]; used for what the owner sees). Refunds from the band fund are
 * paid immediately.
 */
object CostLedger {

    /** Total assigned to refunds so far: event refunds plus fund entries linked to the cost. */
    fun refunded(band: Band, costId: Id): Money = band.events.sumOfMoney { event -> event.refundOf(costId)?.amount ?: Money.ZERO } + fundRefunds(band, costId)

    /** Part of the cost not assigned to any refund yet. */
    fun outstanding(band: Band, cost: Cost): Money = cost.amount - refunded(band, cost.id)

    fun fundRefunds(band: Band, costId: Id): Money = band.fundEntries.filter { it.costId == costId }.sumOfMoney { -it.amount }

    /** Costs may only be refunded at events their owner takes part in. */
    fun isRefundableAt(cost: Cost, event: Event): Boolean = event.participant(cost.memberId) != null

    /** Largest refund the event may give for the cost, counting refunds everywhere except this event. */
    fun maxRefundAt(band: Band, cost: Cost, event: Event): Money = outstanding(band, cost) + (event.refundOf(cost.id)?.amount ?: Money.ZERO)

    /**
     * Outstanding costs of this event's participants, oldest first: the "Waiting" list of the event's
     * Refunds section. Also one refunded here in part, e.g. raised by its owner after it was
     * included: the rest waits for a lead.
     */
    fun pendingFromEarlier(band: Band, event: Event): List<Cost> = band.costs
        .filter { isRefundableAt(it, event) && outstanding(band, it).isPositive }
        .sortedWith(compareBy<Cost> { it.date }.thenBy { it.createdAt })

    /**
     * A member may change their own cost only while no part of it is final: no refund in an event with
     * recorded transfers and no refund from the band fund. Leads are not bound by this.
     */
    fun isLockedForOwner(band: Band, cost: Cost): Boolean = band.events.any { it.refundOf(cost.id) != null && it.transfers.isNotEmpty() } ||
        band.fundEntries.any { it.costId == cost.id }
}
