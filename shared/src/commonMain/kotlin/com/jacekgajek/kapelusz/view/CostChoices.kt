package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.text.sortedByName

/** The choices a cost's form offers: who paid it, and which event it was incurred for. */
class CostChoices(private val view: BandView, private val can: Capabilities) {
    private val band get() = view.band

    /**
     * Members the user may add a cost for (themselves, or anyone active for leads), alphabetical; for a cost of an
     * event ([forEvent]), only those taking part in it.
     */
    fun payers(forEvent: Event?): List<Member> = band.members
        .filter { !it.archived && (forEvent == null || forEvent.participant(it.id) != null) && can.addCostFor(it.id) }
        .sortedByName { it.name }

    /**
     * Events a cost paid by [paidBy] may be "incurred for" (ITERATION_3 R2): only ones they take part in, not archived
     * (unless it is the cost's current one); the cost's own event stays listed while it is still its owner's. `null`
     * (no event) comes first.
     */
    fun events(existing: Cost?, paidBy: Id?, currentEventId: Id?): List<Id?> {
        val keptEventId = existing?.eventId?.takeIf { paidBy == existing.memberId }
        return listOf<Id?>(null) + band.events
            .filter { event ->
                val theirs = paidBy != null && event.participant(paidBy) != null && (!event.archived || event.id == currentEventId)
                event.id == keptEventId || theirs
            }
            .sortedByDescending { it.date }.map { it.id }
    }
}

/** The costs an event's Refunds section lists as waiting (ITERATION_4 §1.2). */
object Refunds {
    /** Outstanding costs of the event's participants a lead may include here, oldest first; none for anyone else. */
    fun pending(view: BandView, event: Event, canEdit: Boolean): List<Cost> {
        if (!canEdit) return emptyList()
        return CostLedger.pendingFromEarlier(view.band, event)
    }

    /**
     * Someone who cannot include costs still sees their own waiting ones, so a cost they just added does not seem lost
     * (and is not added twice): also ones added for this event that cannot be refunded here (their owner does not take
     * part). Not on an archived event (nothing is refunded there any more), nor for viewers (they have no costs).
     */
    fun mine(view: BandView, event: Event, me: Member?, canEdit: Boolean): List<Cost> {
        val band = view.band
        if (canEdit || event.archived) return emptyList()
        if (me == null || me.role == Role.VIEWER) return emptyList()
        val forThisEvent = band.costs.filter { it.eventId == event.id && CostLedger.outstanding(band, it).isPositive }
        return (CostLedger.pendingFromEarlier(band, event) + forThisEvent).distinct().filter { it.memberId == me.id }
            .sortedWith(compareBy<Cost> { it.date }.thenBy { it.createdAt })
    }
}
