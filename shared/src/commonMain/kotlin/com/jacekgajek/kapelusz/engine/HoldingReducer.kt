package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.HoldingOp
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.settlement.VatMath

/** Money received for an event, and fees awaited from the organiser. Pure state transitions and their validation; see [Reducer]. */
internal object HoldingReducer {
    fun apply(band: Band, op: HoldingOp, ctx: OpContext): Band = when (op) {
        is AddHolding -> band.updateEvent(op.eventId) { event ->
            invalidIf(event.holdings.any { it.id == op.holdingId }, RejectionReason.DUPLICATE_ID) { "Holding ${op.holdingId} already exists" }
            // Or the organiser: a fee agreed but not paid yet.
            invalidIf(op.partyId != ORGANISER && event.participant(op.partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "Only participants can hold event money" }
            invalidIf(Artists.isArtist(band, op.partyId), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not hold event money" }
            validatePositive(op.amount, "Holding amount")
            invalidIf(op.vat != null && !VatMath.matches(op.amount, op.vat.net, op.vat.rate), RejectionReason.INVALID_AMOUNT) { "Gross, Net and VAT rate don't match" }
            event.copy(
                holdings = event.holdings + Holding(
                    id = op.holdingId,
                    partyId = op.partyId,
                    amount = op.amount,
                    source = op.source,
                    note = op.note.blankToNull(),
                    recordedBy = ctx.memberId,
                    vat = op.vat,
                ),
            )
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
                        vat = op.vat?.value ?: holding.vat,
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
            // Still the lead's record: who received it can't then remove it, or its contract.
            val received = Holding(
                id = op.receivedId,
                partyId = op.partyId,
                amount = op.amount,
                source = awaited.source,
                note = awaited.note,
                attachments = if (full) awaited.attachments else emptyList(),
                recordedBy = awaited.recordedBy ?: ctx.memberId,
                vat = awaited.vat,
            )
            val rest = if (full) emptyList() else listOf(awaited.copy(amount = awaited.amount - op.amount))
            event.copy(holdings = event.holdings.flatMap { if (it.id == awaited.id) rest + received else listOf(it) })
        }

        is RemoveHolding -> band.updateEvent(op.eventId) { event ->
            event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            event.copy(holdings = event.holdings.filterNot { it.id == op.holdingId })
        }
    }
}
