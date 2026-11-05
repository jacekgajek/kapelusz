package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Vat
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
            val vatFits = op.vat == null || VatMath.matches(op.amount, op.vat.net, op.vat.rate)
            invalidIf(!vatFits, RejectionReason.INVALID_AMOUNT) { "Gross, net and VAT rate don't match" }
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
            val holding = event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            op.amount?.let { validatePositive(it.value, "Holding amount") }
            val updated = holding.copy(
                amount = op.amount?.value ?: holding.amount,
                source = op.source?.value ?: holding.source,
                note = if (op.note != null) op.note.value.blankToNull() else holding.note,
                vat = if (op.vat != null) op.vat.value else holding.vat,
            )
            // As on adding: a new gross without its new net, or a breakdown that does not fit, is refused.
            val vatFits = updated.vat == null || VatMath.matches(updated.amount, updated.vat.net, updated.vat.rate)
            invalidIf(!vatFits, RejectionReason.INVALID_AMOUNT) { "Gross, net and VAT rate don't match" }
            event.copy(holdings = event.holdings.map { if (it.id == op.holdingId) updated else it })
        }

        is ReceiveAwaited -> band.updateEvent(op.eventId) { event ->
            val awaited = event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            invalidIf(!awaited.awaited, RejectionReason.INVALID_INPUT) { "Only an awaited fee can be received" }
            invalidIf(event.holdings.any { it.id == op.receivedId }, RejectionReason.DUPLICATE_ID) { "Holding ${op.receivedId} already exists" }
            validatePositive(op.amount, "Received amount")
            if (op.partyId == BAND_FUND) {
                invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "The band fund is off" }
            } else {
                invalidIf(event.participant(op.partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "Only participants can hold event money" }
                invalidIf(Artists.isArtist(band, op.partyId), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not hold event money" }
            }
            // Whole, as the band sees it now: part of it is a change of the fee first, so the VAT breakdown and the
            // contract go with the money as they are, and an amount changed on another phone meanwhile is caught.
            invalidIf(op.amount != awaited.amount, RejectionReason.INVALID_AMOUNT) { "An awaited fee is received whole" }
            // Still the lead's record: who received it can't then remove it, or its contract.
            val received = Holding(
                id = op.receivedId,
                partyId = op.partyId,
                amount = awaited.amount,
                source = awaited.source,
                note = awaited.note,
                attachments = awaited.attachments,
                recordedBy = awaited.recordedBy ?: ctx.memberId,
                vat = awaited.vat,
            )
            event.copy(holdings = event.holdings.map { if (it.id == awaited.id) received else it })
        }

        is RemoveHolding -> band.updateEvent(op.eventId) { event ->
            event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
            event.copy(holdings = event.holdings.filterNot { it.id == op.holdingId })
        }
    }
}
