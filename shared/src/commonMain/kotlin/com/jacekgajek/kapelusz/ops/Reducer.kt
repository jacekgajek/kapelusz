package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id

/** Who applies an operation, and when. [memberId] is null for server-internal (system) operations. */
data class OpContext(val opId: Id, val timestamp: Long, val memberId: Id?)

/**
 * Pure state transitions with data validation. Permissions are checked separately by
 * [com.jacekgajek.kapelusz.auth.Permissions]. Throws [RejectedException] for invalid operations.
 *
 * The work is done by one reducer per kind of operation ([BandReducer], [RosterReducer], …), each an exhaustive `when`
 * over its group; this dispatches to them and applies what holds for every change: archived events are read-only, the
 * band fund needs a treasurer, pairs are kept consistent, the touched event is stamped.
 */
object Reducer {

    fun apply(band: Band, op: Operation, ctx: OpContext): Band {
        // Archived events are read-only (ITERATION_3 R10): restoring and deleting them are the only changes.
        op.eventId?.let { band.event(it) }?.let { event ->
            conflictIf(event.archived && op !is SetEventArchived && op !is DeleteEvent, RejectionReason.ARCHIVED_EVENT) { "Event ${event.id} is archived" }
        }
        val result = when (op) {
            is BandOp -> BandReducer.apply(band, op, ctx)
            is RosterOp -> RosterReducer.apply(band, op, ctx)
            is PairOp -> PairReducer.apply(band, op)
            is LineupOp -> LineupReducer.apply(band, op, ctx)
            is HoldingOp -> HoldingReducer.apply(band, op, ctx)
            is TransferOp -> TransferReducer.apply(band, op, ctx)
            is EventDetailsOp -> EventReducer.apply(band, op, ctx)
            is EventMoneyOp -> MoneyReducer.apply(band, op)
            is CostOp -> CostReducer.apply(band, op, ctx)
            is FundOp -> FundReducer.apply(band, op)
            is AttachmentOp -> AttachmentReducer.apply(band, op)
            is Undo -> Undoing.apply(band, op)
            is UnknownOperation -> reject(RejectionCode.INVALID, RejectionReason.INVALID_INPUT, "Unknown operation")
        }
        // While the fund is off (ITERATION_3 R10), a change may not leave it holding or owing money: that needs a treasurer.
        conflictIf(
            result.treasurerMemberId == null && band.treasurerMemberId == null && BandFund.canDisable(band) && !BandFund.canDisable(result),
            RejectionReason.NO_TREASURER,
        ) { "Choose a treasurer before using the band fund" }
        // Archiving, unlinking or a role change can end a pair or an invitation (ITERATION_5 §7); an undo is checked instead.
        val tidied = if (op is Undo) result else SettleTogether.tidy(result, band)
        return op.eventId
            ?.takeIf { op !is DeleteEvent }
            ?.let { eventId -> tidied.updateEvent(eventId) { it.copy(updatedAt = ctx.timestamp) } }
            ?: tidied
    }
}
