package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.CostOp
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.settlement.CostLedger

/** Costs a member paid for the band. Pure state transitions and their validation; see [Reducer]. */
internal object CostReducer {
    fun apply(band: Band, op: CostOp, ctx: OpContext): Band = when (op) {
        is AddCost -> addCost(band, op, ctx)
        is UpdateCost -> updateCost(band, op, ctx)
        is DeleteCost -> deleteCost(band, op, ctx)
    }

    private fun addCost(band: Band, op: AddCost, ctx: OpContext): Band {
        invalidIf(band.cost(op.costId) != null, RejectionReason.DUPLICATE_ID) { "Cost ${op.costId} already exists" }
        validateActiveMember(band, op.memberId)
        validatePositive(op.amount, "Cost amount")
        op.incurredForEventId?.let { validateCostEvent(band, it, op.memberId) }
        val cost = Cost(
            id = op.costId,
            memberId = op.memberId,
            amount = op.amount,
            date = op.date,
            eventId = op.incurredForEventId,
            category = op.category,
            description = op.description.blankToNull(),
            createdAt = ctx.timestamp,
        )
        val added = band.copy(costs = band.costs + cost)
        return op.incurredForEventId?.let { includeByLead(added, it, cost.id, ctx) } ?: added
    }

    private fun updateCost(band: Band, op: UpdateCost, ctx: OpContext): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        op.incurredForEventId?.value?.takeIf { it != cost.eventId }?.let { validateCostEvent(band, it, cost.memberId) }
        val newAmount = op.amount?.value ?: cost.amount
        validatePositive(newAmount, "Cost amount")
        var result = band.updateCost(cost.id) {
            it.copy(
                amount = newAmount,
                date = op.date?.value ?: it.date,
                eventId = if (op.incurredForEventId != null) op.incurredForEventId.value else it.eventId,
                category = if (op.category != null) op.category.value else it.category,
                description = if (op.description != null) op.description.value.blankToNull() else it.description,
            )
        }
        // A refund that covered the whole remainder at the cost's own event keeps covering it (not when the cost moves
        // to another event in the same change: its refund there is what counts then).
        val moves = op.incurredForEventId != null && op.incurredForEventId.value != cost.eventId
        val ownEvent = cost.eventId?.takeUnless { moves }?.let { band.event(it) }
        val ownRefund = ownEvent?.refundOf(cost.id)
        // Only while the event is open (I3 R2): once money moved or it is archived, refunds are a lead's decision.
        if (ownEvent != null && ownRefund != null && newAmount != cost.amount && !ownEvent.archived && ownEvent.transfers.isEmpty()) {
            val refundedElsewhere = CostLedger.refunded(band, cost.id) - ownRefund.amount
            if (ownRefund.amount == cost.amount - refundedElsewhere) {
                // Only a lead's edit raises the refund; anyone else's raise waits for a lead (I4 §1.2).
                val adjusted = (newAmount - refundedElsewhere)
                    .let { if (ctx.actsAsLead(band) || it < ownRefund.amount) it else ownRefund.amount }
                invalidIf(adjusted.isNegative, RejectionReason.COST_BELOW_REFUNDED) { "Cost amount is below what was already refunded" }
                result = result.updateEvent(ownEvent.id) { event ->
                    event.copy(
                        refunds = event.refunds.map { if (it.costId == cost.id) it.copy(amount = adjusted) else it },
                        updatedAt = ctx.timestamp,
                    )
                }
            }
        }
        invalidIf(newAmount < CostLedger.refunded(result, cost.id), RejectionReason.COST_BELOW_REFUNDED) { "Cost amount is below what was already refunded" }
        // Moving the cost to another event pre-includes it there, as when it is added.
        val newEventId = op.incurredForEventId?.value
        if (newEventId != null && newEventId != cost.eventId) result = includeByLead(result, newEventId, cost.id, ctx)
        return result
    }

    private fun deleteCost(band: Band, op: DeleteCost, ctx: OpContext): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        conflictIf(band.fundEntries.any { it.costId == cost.id }, RejectionReason.COST_REFUNDED_FROM_FUND) { "Cost was refunded from the band fund" }
        // Its refund in an archived event would change that event (I3 R10).
        conflictIf(band.events.any { it.archived && it.refundOf(cost.id) != null }, RejectionReason.ARCHIVED_EVENT) { "Cost is refunded by an archived event" }
        return band.copy(
            costs = band.costs.filterNot { it.id == cost.id },
            events = band.events.map { event ->
                if (event.refundOf(cost.id) == null) {
                    event
                } else {
                    event.copy(refunds = event.refunds.filterNot { it.costId == cost.id }, updatedAt = ctx.timestamp)
                }
            },
        )
    }

    /** A cost can be incurred only for an event its owner takes part in (I3 R2): only there it is refunded. */
    private fun validateCostEvent(band: Band, eventId: Id, memberId: Id) {
        val event = band.event(eventId).orNotFound { "Event $eventId" }
        invalidIf(event.participant(memberId) == null, RejectionReason.NOT_A_PARTICIPANT) { "The cost's owner does not take part in the event" }
        conflictIf(event.archived, RejectionReason.ARCHIVED_EVENT) { "Event $eventId is archived" }
    }

    /**
     * A cost a lead enters (theirs or anyone's) for an event is included there in full, while the event is still open
     * (I3 R2): once money moved (transfers recorded) or it is archived, refunds are decided one by one. Costs
     * others enter wait for a lead's Refund here (I4 §1.2).
     */
    private fun includeByLead(band: Band, eventId: Id, costId: Id, ctx: OpContext): Band {
        if (!ctx.actsAsLead(band)) return band
        val event = band.event(eventId) ?: return band
        val cost = band.cost(costId) ?: return band
        if (event.participant(cost.memberId) == null || event.archived || event.transfers.isNotEmpty() || event.refundOf(cost.id) != null) return band
        val amount = CostLedger.outstanding(band, cost).takeIf { it.isPositive } ?: return band
        return band.updateEvent(eventId) { it.copy(refunds = it.refunds + Refund(cost.id, amount), updatedAt = ctx.timestamp) }
    }
}
