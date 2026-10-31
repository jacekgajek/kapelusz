package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AttachmentOp
import com.jacekgajek.kapelusz.ops.BandOp
import com.jacekgajek.kapelusz.ops.CostOp
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.EventDetailsOp
import com.jacekgajek.kapelusz.ops.EventMoneyOp
import com.jacekgajek.kapelusz.ops.FundOp
import com.jacekgajek.kapelusz.ops.HoldingOp
import com.jacekgajek.kapelusz.ops.LineupOp
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.PairOp
import com.jacekgajek.kapelusz.ops.RosterOp
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.TransferOp
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnknownOperation
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.SettleTogether

/** Who applies an operation, and when. [memberId] is null for server-internal (system) operations. */
data class OpContext(val opId: Id, val timestamp: Long, val memberId: Id?) {
    /** The system (no member) acts as a lead; so does a lead or the owner. */
    internal fun actsAsLead(band: Band): Boolean = memberId == null || band.member(memberId)?.role?.atLeast(Role.LEAD) == true
}

/**
 * Pure state transitions with data validation. Permissions are checked separately by
 * [com.jacekgajek.kapelusz.rules.Permissions]. Throws [RejectedException] for invalid operations.
 *
 * The work is done by one reducer per kind of operation ([BandReducer], [RosterReducer], …), each an exhaustive `when`
 * over its group; this dispatches to them and applies what holds for every change: archived events are read-only, the
 * band fund needs a treasurer, pairs are kept consistent, the touched event is stamped.
 */
object Reducer {

    fun apply(band: Band, op: Operation, ctx: OpContext): Band {
        // Archived events are read-only (I3 R10): restoring and deleting them are the only changes.
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
        // While the fund is off (I3 R10), a change may not leave it holding or owing money: that needs a treasurer.
        conflictIf(
            result.treasurerMemberId == null && band.treasurerMemberId == null && BandFund.canDisable(band) && !BandFund.canDisable(result),
            RejectionReason.NO_TREASURER,
        ) { "Choose a treasurer before using the band fund" }
        // Archiving, unlinking or a role change can end a pair or an invitation (I5 §7); an undo is checked instead.
        val tidied = if (op is Undo) result else SettleTogether.tidy(result, band)
        return op.eventId
            ?.takeIf { op !is DeleteEvent }
            ?.let { eventId -> tidied.updateEvent(eventId) { it.copy(updatedAt = ctx.timestamp) } }
            ?: tidied
    }
}
