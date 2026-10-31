package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionReason

/** Money paid into the band fund, or refunded from it. Pure state transitions and their validation; see [Reducer]. */
internal object FundReducer {
    fun apply(band: Band, op: FundOp): Band = when (op) {
        is AddFundEntry -> addFundEntry(band, op)

        is UpdateFundEntry -> updateFundEntry(band, op)

        is RemoveFundEntry -> {
            band.fundEntries.firstOrNull { it.id == op.entryId }.orNotFound { "Fund entry ${op.entryId}" }
            band.copy(fundEntries = band.fundEntries.filterNot { it.id == op.entryId })
        }
    }

    private fun addFundEntry(band: Band, op: AddFundEntry): Band {
        invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "Choose a treasurer before using the band fund" }
        invalidIf(band.fundEntries.any { it.id == op.entryId }, RejectionReason.DUPLICATE_ID) { "Fund entry ${op.entryId} already exists" }
        invalidIf(op.amount.isZero, RejectionReason.INVALID_AMOUNT) { "Fund entry amount cannot be zero" }
        // Money leaves the fund only to refund a cost (ITERATION_7 §5.1).
        invalidIf(op.amount.isNegative && op.costId == null, RejectionReason.INVALID_INPUT) { "Money out of the fund refunds a cost" }
        op.costId?.let { costId ->
            val cost = band.cost(costId).orNotFound { "Cost $costId" }
            invalidIf(!op.amount.isNegative, RejectionReason.INVALID_AMOUNT) { "A cost refund from the fund must be negative" }
            invalidIf(-op.amount > CostLedger.outstanding(band, cost), RejectionReason.REFUND_TOO_LARGE) { "Refund exceeds the outstanding amount" }
        }
        validateFundPayer(band, op.amount, op.paidBy)
        return band.copy(fundEntries = band.fundEntries + FundEntry(op.entryId, op.date, op.description.validName("Description"), op.amount, op.costId, op.paidBy))
    }

    /** Who paid money in: a member of the band, or nobody (from outside); a refund has nobody (its cost names whose). */
    private fun validateFundPayer(band: Band, amount: Money, paidBy: Id?) {
        if (paidBy == null) return
        invalidIf(!amount.isPositive, RejectionReason.INVALID_INPUT) { "Only money paid in has a payer" }
        band.member(paidBy).orNotFound { "Member $paidBy" }
    }

    private fun updateFundEntry(band: Band, op: UpdateFundEntry): Band {
        val entry = band.fundEntries.firstOrNull { it.id == op.entryId }.orNotFound { "Fund entry ${op.entryId}" }
        val updated = entry.copy(
            date = op.date?.value ?: entry.date,
            description = op.description?.value?.validName("Description") ?: entry.description,
            amount = op.amount?.value ?: entry.amount,
            paidBy = if (op.paidBy != null) op.paidBy.value else entry.paidBy,
        )
        invalidIf(updated.amount.isZero, RejectionReason.INVALID_AMOUNT) { "Fund entry amount cannot be zero" }
        // Money in stays money in, a refund a refund.
        invalidIf(updated.amount.isPositive != entry.amount.isPositive, RejectionReason.INVALID_AMOUNT) { "A fund entry keeps its direction" }
        if (op.paidBy != null) validateFundPayer(band, updated.amount, updated.paidBy)
        updated.costId?.let { costId ->
            val cost = band.cost(costId).orNotFound { "Cost $costId" }
            invalidIf(!updated.amount.isNegative, RejectionReason.INVALID_AMOUNT) { "A cost refund from the fund must be negative" }
            invalidIf(-updated.amount > CostLedger.outstanding(band, cost) - entry.amount, RejectionReason.REFUND_TOO_LARGE) { "Refund exceeds the outstanding amount" }
        }
        return band.copy(fundEntries = band.fundEntries.map { if (it.id == entry.id) updated else it })
    }
}
