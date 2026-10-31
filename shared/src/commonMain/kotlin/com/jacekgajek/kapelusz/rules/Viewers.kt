package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.settlement.SettledEvents

/** Viewers see everything but record nothing, transfers included. */
object Viewers {

    /**
     * The member may become a viewer (I5 B2): they don't play in an event not settled yet (whose money they could not
     * pass on) and don't hold the band fund.
     */
    fun mayBecome(view: SettledEvents, memberId: Id): Boolean = view.band.treasurerMemberId != memberId && view.band.events.none { event ->
        !event.archived && event.participant(memberId) != null && !view.isSettled(event)
    }
}
