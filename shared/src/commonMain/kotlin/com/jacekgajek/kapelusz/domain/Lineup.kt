package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.ChangeLineup

/** The line-up picker (ITERATION_8 §1): who can be picked, what Start from… does, what Save sends. */
object Lineup {

    /** Who can take part: current members, not viewers, in alphabetical order. */
    fun candidates(band: Band): List<Member> = band.members.filter { !it.archived && it.role != Role.VIEWER }.sortedByName { it.name }

    /**
     * [partyId] has money in [event] (collected, transfers, refunds of their costs): taking them out is refused
     * ([com.jacekgajek.kapelusz.ops.RemoveParticipant]), so their tick is locked.
     */
    fun locked(band: Band, event: Event, partyId: Id): Boolean =
        event.holdings.any { it.partyId == partyId } ||
            event.transfers.any { it.from == partyId || it.to == partyId } ||
            event.refunds.any { band.cost(it.costId)?.memberId == partyId }

    /**
     * The picker's state: [ticked] members and guests (this event's, and [newGuests]); [shares] for those not in the
     * event yet who don't take their default share (from Start from…).
     */
    data class Draft(val ticked: Set<Id>, val shares: Map<Id, Participant> = emptyMap(), val newGuests: List<Guest> = emptyList()) {
        companion object {
            fun of(event: Event) = Draft(event.participants.map { it.partyId }.toSet())
        }
    }

    /**
     * [source]'s line-up in place of the ticks (ITERATION_8 §1.2): its members who can take part, its guests as new
     * guests (one of the same name already here kept instead), whoever is locked here still ticked. Newcomers take the
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
        ticked += event.participants.map { it.partyId }.filter { locked(band, event, it) }
        return Draft(ticked, shares, newGuests)
    }

    /** What Save sends: who joins (with their share) and who leaves; null when nothing changes. */
    fun change(band: Band, event: Event, draft: Draft): ChangeLineup? {
        val current = event.participants.map { it.partyId }.toSet()
        val remove = current.filter { it !in draft.ticked && !locked(band, event, it) }
        val newGuests = draft.newGuests.filter { it.id in draft.ticked }
        val add = draft.ticked.filter { it !in current }.mapNotNull { id ->
            draft.shares[id] ?: band.member(id)?.takeIf { !it.archived && it.role != Role.VIEWER }?.asParticipant()
        }
        if (add.isEmpty() && remove.isEmpty()) return null
        return ChangeLineup(event.id, add, remove, newGuests)
    }
}
