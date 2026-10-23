package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id

/** Viewers see everything but record nothing, transfers included. */
object Viewers {

    /**
     * The member may become a viewer (V5-M1 B2): they don't play in an event not settled yet (whose money they could not
     * pass on) and don't hold the band fund.
     */
    fun mayBecome(band: Band, memberId: Id): Boolean =
        band.treasurerMemberId != memberId && band.events.none { event ->
            !event.archived && event.participant(memberId) != null && SettlementCalculator.settle(band, event).status != EventStatus.SETTLED
        }
}
