package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.ChangeLineup
import com.jacekgajek.kapelusz.ops.LineupOp
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.RenameGuest
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetWeight

/** Who takes part in an event, and with what share. Pure state transitions and their validation; see [Reducer]. */
internal object LineupReducer {
    fun apply(band: Band, op: LineupOp, ctx: OpContext): Band = when (op) {
        is AddParticipant -> addParticipant(band, op, ctx)

        is AddGuest -> addGuest(band, op)

        is RenameGuest -> band.updateEvent(op.eventId) { event ->
            event.guest(op.guestId).orNotFound { "Guest ${op.guestId}" }
            event.copy(guests = event.guests.map { if (it.id == op.guestId) it.copy(name = op.name.validName("Guest name")) else it })
        }

        is SetWeight -> band.updateEvent(op.eventId) { event ->
            event.participant(op.partyId).orNotFound { "Participant ${op.partyId}" }
            validateWeight(op.weight)
            event.copy(participants = event.participants.map { if (it.partyId == op.partyId) it.copy(weight = op.weight) else it })
        }

        is SetFixedShare -> band.updateEvent(op.eventId) { event ->
            val participant = event.participant(op.partyId).orNotFound { "Participant ${op.partyId}" }
            val share = validShare(op.weight ?: participant.weight, op.amount, op.percent)
            event.copy(participants = event.participants.map { if (it.partyId == op.partyId) share.applyTo(it) else it })
        }

        is RemoveParticipant -> removeParticipant(band, op)

        is ChangeLineup -> changeLineup(band, op, ctx)
    }

    private fun addParticipant(band: Band, op: AddParticipant, ctx: OpContext): Band {
        validateActiveMember(band, op.memberId)
        validateNotViewer(band, op.memberId)
        val share = validShare(op.weight, op.fixedShare, op.percent)
        val updated = band.updateEvent(op.eventId) { event ->
            invalidIf(event.participant(op.memberId) != null, RejectionReason.ALREADY_PARTICIPANT) { "Member already takes part" }
            event.copy(participants = event.participants + share.applyTo(Participant(op.memberId, addedBy = ctx.memberId)))
        }
        return updated
    }

    private fun addGuest(band: Band, op: AddGuest): Band = band.updateEvent(op.eventId) { event ->
        invalidIf(event.guest(op.guestId) != null || band.member(op.guestId) != null, RejectionReason.DUPLICATE_ID) { "Id ${op.guestId} already in use" }
        val share = validShare(op.weight, op.fixedShare, op.percent)
        event.copy(
            guests = event.guests + Guest(op.guestId, op.name.validName("Guest name")),
            participants = event.participants + share.applyTo(Participant(op.guestId)),
        )
    }

    /**
     * One by one, as [removeParticipant], [addParticipant] and [addGuest] would; the first refusal refuses all of it.
     */
    private fun changeLineup(band: Band, op: ChangeLineup, ctx: OpContext): Band {
        val ids = op.add.map { it.partyId } + op.remove
        invalidIf(ids.isEmpty(), RejectionReason.INVALID_INPUT) { "Nothing to change" }
        invalidIf(ids.toSet().size != ids.size, RejectionReason.INVALID_INPUT) { "Someone is named twice" }
        invalidIf(ids.size > Limits.MAX_PARTIES_PER_EVENT * 2, RejectionReason.INVALID_INPUT) { "Too many changes" }
        val guests = op.guests.associateBy { it.id }
        invalidIf(guests.size != op.guests.size, RejectionReason.DUPLICATE_ID) { "Duplicate guest ids" }
        invalidIf(guests.keys.any { id -> op.add.none { it.partyId == id } }, RejectionReason.INVALID_INPUT) { "A new guest without a share" }
        val removed = op.remove.fold(band) { result, partyId -> removeParticipant(result, RemoveParticipant(op.eventId, partyId)) }
        return op.add.fold(removed) { result, p ->
            val guest = guests[p.partyId]
            if (guest != null) {
                addGuest(result, AddGuest(op.eventId, guest.id, guest.name, p.weight, p.fixedShare, p.percent))
            } else {
                addParticipant(result, AddParticipant(op.eventId, p.partyId, p.weight, p.fixedShare, p.percent), ctx)
            }
        }
    }

    private fun removeParticipant(band: Band, op: RemoveParticipant): Band = band.updateEvent(op.eventId) { event ->
        event.participant(op.partyId).orNotFound { "Participant ${op.partyId}" }
        conflictIf(event.holdings.any { it.partyId == op.partyId }, RejectionReason.PARTICIPANT_HAS_MONEY) { "Participant holds event money; remove it (and its transfers) first" }
        conflictIf(event.transfers.any { it.from == op.partyId || it.to == op.partyId }, RejectionReason.PARTICIPANT_HAS_TRANSFERS) { "Participant has recorded transfers" }
        conflictIf(event.refunds.any { band.cost(it.costId)?.memberId == op.partyId }, RejectionReason.PARTICIPANT_HAS_REFUNDS) { "Participant has refunds here; defer them first" }
        event.copy(
            participants = event.participants.filterNot { it.partyId == op.partyId },
            guests = event.guests.filterNot { it.id == op.partyId },
        )
    }
}
