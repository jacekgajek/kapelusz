package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionReason

/** What an event pays out before the shares: cost refunds and the band fund's cut. Pure state transitions and their validation; see [Reducer]. */
internal object MoneyReducer {
    fun apply(band: Band, op: EventMoneyOp): Band = when (op) {
        is SetRefund -> setRefund(band, op)

        is SetFundAmount -> band.updateEvent(op.eventId) { event ->
            op.percent?.let(::validateFundPercent)
            // 0% is no cut at all, like an amount of 0.
            val percent = op.percent?.takeIf { it > 0 }
            val amount = if (op.percent != null) Money.ZERO else op.amount
            invalidIf((percent != null || !amount.isZero) && band.treasurerMemberId == null, RejectionReason.NO_TREASURER) {
                "Choose a treasurer before using the band fund"
            }
            event.copy(fundAmount = amount, fundPercent = percent)
        }
    }

    private fun setRefund(band: Band, op: SetRefund): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        return band.updateEvent(op.eventId) { event ->
            invalidIf(op.amount.isNegative, RejectionReason.INVALID_AMOUNT) { "Refund cannot be negative" }
            if (op.amount.isZero) return@updateEvent event.copy(refunds = event.refunds.filterNot { it.costId == cost.id })
            invalidIf(!CostLedger.isRefundableAt(cost, event), RejectionReason.NOT_REFUNDABLE_HERE) { "Costs can only be refunded at events their owner takes part in" }
            val max = CostLedger.maxRefundAt(band, cost, event)
            invalidIf(op.amount > max, RejectionReason.REFUND_TOO_LARGE) { "Refund ${op.amount} exceeds outstanding $max" }
            val refunds = if (event.refundOf(cost.id) != null) {
                event.refunds.map { if (it.costId == cost.id) it.copy(amount = op.amount) else it }
            } else {
                event.refunds + Refund(cost.id, op.amount)
            }
            event.copy(refunds = refunds)
        }
    }
}
