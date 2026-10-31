package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sumOfMoney

/**
 * Paid amounts and statuses of costs for one band state ([BandView.costStatuses]): which events are settled comes from
 * the view, computed once, so listing many costs does not settle every event for every cost.
 */
class CostStatuses(private val view: BandView) {
    private val band: Band get() = view.band
    private val settledEventIds: Set<Id> get() = view.settledEventIds

    /** Until the app carries the view (REFACTORING.md step 1.6). */
    constructor(band: Band) : this(BandView(band))

    fun isSettled(event: Event): Boolean = event.id in settledEventIds

    fun paid(costId: Id): Money = band.events.filter { it.id in settledEventIds }.sumOfMoney { it.refundOf(costId)?.amount ?: Money.ZERO } +
        CostLedger.fundRefunds(band, costId)

    fun unpaid(cost: Cost): Money = cost.amount - paid(cost.id)

    fun status(cost: Cost): CostStatus {
        val paid = paid(cost.id)
        return when {
            paid >= cost.amount -> CostStatus.REFUNDED
            paid.isPositive -> CostStatus.PARTLY_REFUNDED
            CostLedger.refunded(band, cost.id).isPositive -> CostStatus.REFUND_PLANNED
            else -> CostStatus.PENDING
        }
    }
}
