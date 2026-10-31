package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.SettledEvents
import com.jacekgajek.kapelusz.settlement.Settlement
import com.jacekgajek.kapelusz.settlement.SettlementCalculator

/**
 * One band state, with what is derived from it computed at most once: lookups by id, each event's settlement, which
 * events are settled, the costs' statuses. A new band state gets a new view; nothing here is recomputed for a band
 * that did not change. Meant for one thread at a time (the app's main thread, one server request), like the band
 * itself.
 *
 * Settlements are cached by event id, so [settlement] is for the events of [band]: a draft event (a preview of a change)
 * goes to [SettlementCalculator.settle] directly.
 */
class BandView(override val band: Band) : SettledEvents {
    private val membersById: Map<Id, Member> = band.members.associateBy { it.id }
    private val eventsById: Map<Id, Event> = band.events.associateBy { it.id }
    private val costsById: Map<Id, Cost> = band.costs.associateBy { it.id }
    private val settlements = HashMap<Id, Settlement>()

    fun member(id: Id): Member? = membersById[id]

    fun event(id: Id): Event? = eventsById[id]

    fun cost(id: Id): Cost? = costsById[id]

    /** The event's settlement, computed once per view. */
    fun settlement(event: Event): Settlement = settlements.getOrPut(event.id) { SettlementCalculator.settle(band, event) }

    override fun isSettled(event: Event): Boolean = settlement(event).status == EventStatus.SETTLED

    /** Ids of the settled events; every event is settled once to find out. */
    val settledEventIds: Set<Id> by lazy { band.events.filter { isSettled(it) }.mapTo(HashSet()) { it.id } }

    /** Paid amounts and statuses of the band's costs, built on [settledEventIds]. */
    val costStatuses: CostStatuses by lazy { CostStatuses(this) }
}
