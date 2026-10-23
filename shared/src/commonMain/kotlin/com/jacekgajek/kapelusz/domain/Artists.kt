package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role

/** Artists never hold money (ITERATION_4 §1.1): no collected money, no payments out, no band fund. */
object Artists {

    fun isArtist(band: Band, partyId: Id): Boolean = band.member(partyId)?.role == Role.ARTIST

    /**
     * The member holds money someone may still ask for: the band fund, or collected (or paid out) money in an event
     * not settled yet. Settled events don't count: making someone an artist after them is fine.
     */
    fun holdsMoney(band: Band, memberId: Id): Boolean =
        band.treasurerMemberId == memberId || band.events.any { event ->
            (event.holdings.any { it.partyId == memberId } || event.transfers.any { it.from == memberId }) &&
                SettlementCalculator.settle(band, event).status != EventStatus.SETTLED
        }
}
