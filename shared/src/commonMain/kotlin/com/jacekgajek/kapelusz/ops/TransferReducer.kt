package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.domain.SuggestedTransfer
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Transfer

/** Money handed between parties to settle an event, by hand or from the suggestions. Pure state transitions and their validation; see [Reducer]. */
internal object TransferReducer {
    fun apply(band: Band, op: TransferOp, ctx: OpContext): Band = when (op) {
        is RecordTransfer -> if (op.together) {
            recordTogether(band, op, ctx)
        } else {
            band.updateEvent(op.eventId) { event ->
                invalidIf(event.transfers.any { it.id == op.transferId }, RejectionReason.DUPLICATE_ID) { "Transfer ${op.transferId} already exists" }
                validateTransferParty(band, event, op.from)
                validateTransferParty(band, event, op.to)
                invalidIf(op.from == op.to, RejectionReason.SAME_PARTY) { "Cannot transfer to the same party" }
                validatePositive(op.amount, "Transfer amount")
                validateArtistsPaid(band, event, listOf(SuggestedTransfer(op.from, op.to, op.amount)))
                event.copy(transfers = event.transfers + Transfer(op.transferId, op.from, op.to, op.amount, op.note.blankToNull(), ctx.timestamp, ctx.memberId))
            }
        }

        is SettleSuggestions -> settleSuggestions(band, op, ctx)

        is RemoveTransfer -> band.updateEvent(op.eventId) { event ->
            event.transfers.firstOrNull { it.id == op.transferId }.orNotFound { "Transfer ${op.transferId}" }
            event.copy(transfers = event.transfers.filterNot { it.id == op.transferId })
        }
    }

    private fun settleSuggestions(band: Band, op: SettleSuggestions, ctx: OpContext): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        val settlement = SettlementCalculator.settle(band, event)
        conflictIf(!settlement.canSettle, RejectionReason.CANNOT_SETTLE) { "Event cannot be settled (status ${settlement.status}, problems ${settlement.problems})" }
        val suggestions = op.partyId?.let { settlement.suggestionsFor(it) } ?: settlement.suggestions
        // A pair's transfer is recorded between real people, maybe as two (ITERATION_5 §7), each split against what the
        // ones before it left: who holds what, who is still owed.
        var result = band
        suggestions.forEachIndexed { index, suggestion ->
            val now = result.event(event.id)!!
            val parts = SettleTogether.split(result, SettlementCalculator.settle(result, now), suggestion.from, suggestion.to, suggestion.amount)
            val transfers = parts.mapIndexed { part, it ->
                Transfer("${ctx.opId}-$index" + if (part > 0) "-$part" else "", it.from, it.to, it.amount, null, ctx.timestamp, ctx.memberId)
            }
            // Ids made from the op's id: an op id seen before (a resent op) must not make two transfers one id (V5-M1 B3c).
            invalidIf(transfers.any { t -> now.transfers.any { it.id == t.id } }, RejectionReason.DUPLICATE_ID) { "Transfer ids already exist" }
            validateArtistsPaid(result, now, parts)
            result = result.updateEvent(event.id) { it.copy(transfers = it.transfers + transfers) }
        }
        return result
    }

    /** A suggestion's transfer, settled or in part, where a pair is either partner (ITERATION_5 §7). */
    private fun recordTogether(band: Band, op: RecordTransfer, ctx: OpContext): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        validateTransferParty(band, event, op.from)
        validateTransferParty(band, event, op.to)
        validatePositive(op.amount, "Transfer amount")
        val settlement = SettlementCalculator.settle(band, event)
        invalidIf(op.from == op.to || settlement.party(op.from)?.let { op.to in it.partyIds } == true, RejectionReason.SAME_PARTY) { "Cannot transfer to the same party" }
        val parts = SettleTogether.split(band, settlement, op.from, op.to, op.amount)
        val transfers = parts.mapIndexed { index, part ->
            Transfer(if (index == 0) op.transferId else "${op.transferId}-$index", part.from, part.to, part.amount, op.note.blankToNull(), ctx.timestamp, ctx.memberId)
        }
        invalidIf(transfers.any { t -> event.transfers.any { it.id == t.id } }, RejectionReason.DUPLICATE_ID) { "Transfer ${op.transferId} already exists" }
        validateArtistsPaid(band, event, parts)
        return band.updateEvent(event.id) { it.copy(transfers = it.transfers + transfers) }
    }

    /**
     * An artist is paid what they are owed, never more, and pays only what they were paid too much (ITERATION_4 §1.1):
     * e.g. paid their share, which a refund accepted later made smaller (2026-10-27). They hold no money either way.
     */
    private fun validateArtistsPaid(band: Band, event: Event, parts: List<SuggestedTransfer>) {
        val settlement = SettlementCalculator.settle(band, event)
        parts.forEach { part ->
            if (Artists.isArtist(band, part.from)) {
                val owes = settlement.line(part.from)?.delta?.takeIf { it.isPositive } ?: Money.ZERO
                invalidIf(part.amount > owes, RejectionReason.ARTIST_HOLDS_NO_MONEY) { "More than the artist was paid too much" }
            }
            if (Artists.isArtist(band, part.to)) {
                val owed = settlement.line(part.to)?.delta?.let { -it } ?: Money.ZERO
                invalidIf(part.amount > owed, RejectionReason.ARTIST_HOLDS_NO_MONEY) { "More than the artist is owed" }
            }
        }
    }

    private fun validateTransferParty(band: Band, event: Event, partyId: Id) {
        if (partyId == BAND_FUND) {
            invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "Choose a treasurer before using the band fund" }
        } else {
            invalidIf(event.participant(partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "$partyId does not take part in the event" }
        }
    }
}
