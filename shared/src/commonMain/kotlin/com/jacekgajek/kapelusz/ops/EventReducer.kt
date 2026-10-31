package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason

/** An event as a whole: creating, editing, archiving and deleting it, its agenda. Pure state transitions and their validation; see [Reducer]. */
internal object EventReducer {
    fun apply(band: Band, op: EventDetailsOp, ctx: OpContext): Band = when (op) {
        is CreateEvent -> createEvent(band, op, ctx)

        is UpdateEvent -> band.updateEvent(op.eventId) { event ->
            val location = if (op.location != null) op.location.value.blankToNull() else event.location
            event.copy(
                name = op.name?.value?.validName("Event name") ?: event.name,
                date = op.date?.value ?: event.date,
                location = location,
                notes = if (op.notes != null) op.notes.value.blankToNull() else event.notes,
            )
        }

        is SetEventArchived -> band.updateEvent(op.eventId) { event ->
            conflictIf(op.archived && !event.archived && !SettlementCalculator.settle(band, event).canArchive, RejectionReason.EVENT_UNSETTLED) {
                "Event ${event.id} is not settled"
            }
            event.copy(archived = op.archived)
        }

        is SetEventAgenda -> band.updateEvent(op.eventId) { it.copy(agenda = validAgenda(op.agenda)) }

        is DeleteEvent -> deleteEvent(band, op)
    }

    private fun createEvent(band: Band, op: CreateEvent, ctx: OpContext): Band {
        invalidIf(band.event(op.eventId) != null, RejectionReason.DUPLICATE_ID) { "Event ${op.eventId} already exists" }
        val guestIds = op.guests.map { it.id }.toSet()
        invalidIf(guestIds.size != op.guests.size, RejectionReason.DUPLICATE_ID) { "Duplicate guest ids" }
        invalidIf(guestIds.any { band.member(it) != null }, RejectionReason.DUPLICATE_ID) { "Guest id already used by a member" }
        invalidIf(op.participants.map { it.partyId }.toSet().size != op.participants.size, RejectionReason.INVALID_INPUT) { "Duplicate participants" }
        op.participants.forEach { participant ->
            validShare(participant.weight, participant.fixedShare, participant.percent)
            if (participant.partyId !in guestIds) {
                validateActiveMember(band, participant.partyId)
                validateNotViewer(band, participant.partyId)
            }
        }
        val event = Event(
            id = op.eventId,
            name = op.name.validName("Event name"),
            date = op.date,
            location = op.location.blankToNull(),
            notes = op.notes.blankToNull(),
            guests = op.guests.map { it.copy(name = it.name.validName("Guest name")) },
            // Who added each participant (the creator); was for the reminder prompt (ITERATION_2 §5.2), gone since ITERATION_6 §9.
            participants = op.participants.map { validShare(it.weight, it.fixedShare, it.percent).applyTo(it).copy(addedBy = ctx.memberId) },
            agenda = validAgenda(op.agenda),
        )
        // The band's default, incidental to creating the event: dropped if the fund was turned off meanwhile (e.g. on
        // another phone), rather than refusing the event.
        val fund = op.fund?.let(::validFundCut)?.takeIf { band.treasurerMemberId != null }
        return band.copy(events = band.events + event.copy(fundAmount = fund?.amount ?: Money.ZERO, fundPercent = fund?.percent))
    }

    private fun deleteEvent(band: Band, op: DeleteEvent): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        conflictIf(event.transfers.isNotEmpty(), RejectionReason.EVENT_HAS_TRANSFERS) { "Event has recorded transfers; archive it instead" }
        return band.copy(
            events = band.events.filterNot { it.id == event.id },
            costs = band.costs.map { if (it.eventId == event.id) it.copy(eventId = null) else it },
        )
    }
}
