package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.ChangeLineup

/** The line-up picker (ITERATION_8 §1): who can be picked, what From an event… does, what Save sends. */
object Lineup {

    /** Who can take part: current members, not viewers, in alphabetical order. */
    fun candidates(band: Band): List<Member> = band.members.filter { !it.archived && it.role != Role.VIEWER }.sortedByName { it.name }

    /**
     * [partyId] has money in [event] (collected, transfers, refunds of their costs): taking them out is refused
     * ([com.jacekgajek.kapelusz.ops.RemoveParticipant]), so their tick is locked.
     */
    fun locked(band: Band, event: Event, partyId: Id): Boolean = event.holdings.any { it.partyId == partyId } ||
        event.transfers.any { it.from == partyId || it.to == partyId } ||
        event.refunds.any { band.cost(it.costId)?.memberId == partyId }

    /**
     * The picker's changes, on top of the event as it is now, so a change synced from elsewhere meanwhile stays: who
     * the leader [added] and [removed], [shares] for newcomers not taking their default share (from From an event…), and
     * the [newGuests] From an event… brought.
     */
    data class Draft(
        val added: Set<Id> = emptySet(),
        val removed: Set<Id> = emptySet(),
        val shares: Map<Id, Participant> = emptyMap(),
        val newGuests: List<Guest> = emptyList(),
    ) {
        /** Who is ticked: the event's participants as they are now, with the changes. */
        fun ticked(event: Event): Set<Id> = event.participants.map { it.partyId }.toSet() - removed + added

        fun toggled(event: Event, id: Id): Draft {
            val inEvent = event.participant(id) != null
            return if (id in ticked(event)) {
                copy(added = added - id, removed = if (inEvent) removed + id else removed)
            } else {
                copy(added = if (inEvent) added else added + id, removed = removed - id)
            }
        }

        /** The changes that make [ticked] the ticks for [event]. */
        fun ticking(event: Event, ticked: Set<Id>): Draft {
            val current = event.participants.map { it.partyId }.toSet()
            return copy(added = ticked - current, removed = current - ticked)
        }
    }

    /**
     * [source]'s line-up in place of the ticks (ITERATION_8 §1.2): its members who can take part, its guests as new
     * guests (one of the same name already here kept instead), whoever is locked here (or can't be added back) still
     * ticked. Newcomers take the
     * share they had there; who is already here keeps theirs. [newId] names each new guest.
     */
    fun startFrom(band: Band, event: Event, source: Event, newId: () -> Id): Draft {
        val candidates = candidates(band).map { it.id }.toSet()
        val shares = mutableMapOf<Id, Participant>()
        val ticked = mutableSetOf<Id>()
        val newGuests = mutableListOf<Guest>()
        for (participant in source.participants) {
            val guest = source.guest(participant.partyId)
            when {
                guest != null -> {
                    val here = event.guests.firstOrNull { it.name.trim().equals(guest.name.trim(), ignoreCase = true) && event.participant(it.id) != null }
                    if (here != null) {
                        ticked += here.id
                    } else {
                        val copy = Guest(newId(), guest.name)
                        newGuests += copy
                        ticked += copy.id
                        shares[copy.id] = participant.copy(partyId = copy.id, addedBy = null)
                    }
                }

                participant.partyId in candidates -> {
                    ticked += participant.partyId
                    if (event.participant(participant.partyId) == null) shares[participant.partyId] = participant.copy(addedBy = null)
                }
            }
        }
        // Locked here, or here though no longer someone who could be added (a former member, now a viewer): stays.
        ticked += event.participants.map { it.partyId }.filter { locked(band, event, it) || (event.guest(it) == null && it !in candidates) }
        return Draft(shares = shares, newGuests = newGuests).ticking(event, ticked)
    }

    /** What Save sends: who joins (with their share) and who leaves; null when nothing changes. */
    fun change(band: Band, event: Event, draft: Draft): ChangeLineup? {
        val current = event.participants.map { it.partyId }.toSet()
        val ticked = draft.ticked(event)
        val remove = current.filter { it !in ticked && !locked(band, event, it) }
        val newGuests = draft.newGuests.filter { it.id in ticked }
        val add = ticked.filter { it !in current }.mapNotNull { id ->
            draft.shares[id] ?: band.member(id)?.takeIf { !it.archived && it.role != Role.VIEWER }?.asParticipant()
        }
        if (add.isEmpty() && remove.isEmpty()) return null
        return ChangeLineup(event.id, add, remove, newGuests)
    }
}
