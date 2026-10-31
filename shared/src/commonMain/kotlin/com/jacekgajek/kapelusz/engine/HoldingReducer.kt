package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.HoldingOp
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.rules.Artists

/** Money received for an event, and fees awaited from the organiser. Pure state transitions and their validation; see [Reducer]. */
internal object HoldingReducer {
    fun apply(band: Band, op: HoldingOp, ctx: OpContext): Band = when (op) {
        is AddHolding -> band.updateEvent(op.eventId) { event ->
            invalidIf(event.holdings.any { it.id == op.holdingId }, RejectionReason.DUPLICATE_ID) { "Holding ${op.holdingId} already exists" }
            // Or the organiser: a fee agreed but not paid yet (ITERATION_4 §2.2).
            invalidIf(op.partyId != ORGANISER && event.participant(op.partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "Only participants can hold event money" }
            invalidIf(Artists.isArtist(band, op.partyId), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not hold event money" }
            validatePositive(op.amount, "Holding amount")
            event.copy(holdings = event.holdings + Holding(op.holdingId, op.partyId, op.amount, op.source, op.note.blankToNull(), recordedBy = ctx.memberId))
        }

        is UpdateHolding -> band.updateEvent(op.eventId) { event ->
            event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            op.amount?.let { validatePositive(it.value, "Holding amount") }
            event.copy(
                holdings = event.holdings.map { holding ->
                    if (holding.id != op.holdingId) return@map holding
                    holding.copy(
                        amount = op.amount?.value ?: holding.amount,
                        source = op.source?.value ?: holding.source,
                        note = if (op.note != null) op.note.value.blankToNull() else holding.note,
                    )
                },
            )
        }

        is ReceiveAwaited -> band.updateEvent(op.eventId) { event ->
            val awaited = event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            invalidIf(!awaited.awaited, RejectionReason.INVALID_INPUT) { "Only an awaited fee can be received" }
            invalidIf(event.holdings.any { it.id == op.receivedId }, RejectionReason.DUPLICATE_ID) { "Holding ${op.receivedId} already exists" }
            validatePositive(op.amount, "Received amount")
            invalidIf(op.amount > awaited.amount, RejectionReason.INVALID_AMOUNT) { "More than is awaited" }
            if (op.partyId == BAND_FUND) {
                invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "The band fund is off" }
            } else {
                invalidIf(event.participant(op.partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "Only participants can hold event money" }
                invalidIf(Artists.isArtist(band, op.partyId), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not hold event money" }
            }
            val full = op.amount == awaited.amount
            // Its files (the contract) go with the money once all of it is in.
            // Still the lead's record: who received it can't then remove it, or its contract (ITERATION_7 §3.3).
            val received = Holding(op.receivedId, op.partyId, op.amount, awaited.source, awaited.note, if (full) awaited.attachments else emptyList(), awaited.recordedBy ?: ctx.memberId)
            val rest = if (full) emptyList() else listOf(awaited.copy(amount = awaited.amount - op.amount))
            event.copy(holdings = event.holdings.flatMap { if (it.id == awaited.id) rest + received else listOf(it) })
        }

        is RemoveHolding -> band.updateEvent(op.eventId) { event ->
            event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            event.copy(holdings = event.holdings.filterNot { it.id == op.holdingId })
        }
    }
}
