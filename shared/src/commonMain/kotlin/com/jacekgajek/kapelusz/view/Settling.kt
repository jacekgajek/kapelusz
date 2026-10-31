package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.Settlement
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import kotlinx.datetime.LocalDate

/** An event with its settlement, as the Settle tab lists them. */
data class SettlingEvent(val event: Event, val settlement: Settlement)

/** One of the user's own transfers still to make, with the event it settles. */
data class OwnSettling(val event: Event, val settlement: Settlement, val suggestions: List<SuggestedTransfer>)

/**
 * The Settle tab (I1 §4.1): what [me] still has to pay or receive, what they hold, and for leads what is
 * open at all. Computed once per band state and user; the screen only draws it.
 */
class Settling(view: BandView, private val me: Member?, can: Capabilities, today: LocalDate) {
    private val band = view.band

    /** Events not archived, newest first, each with its settlement. */
    val active: List<SettlingEvent> = band.events.filter { !it.archived }.sortedByDescending { it.date }
        .map { SettlingEvent(it, view.settlement(it)) }

    /**
     * Transfers the user may record where they are a side (a pair's are both partners', I5 §7), only in
     * events that can be settled; a viewer gets none.
     */
    val mine: List<OwnSettling> = active.mapNotNull { (event, settlement) ->
        val myParties = (me?.let { settlement.party(it.id)?.partyIds } ?: listOfNotNull(me?.id)) +
            listOfNotNull(BAND_FUND.takeIf { me != null && band.treasurerMemberId == me.id })
        val suggestions = settlement.suggestions
            .filter { (it.from in myParties || it.to in myParties) && can.settleSuggestion(event, it.from, it.to) }
        if (settlement.canSettle && suggestions.isNotEmpty()) OwnSettling(event, settlement, suggestions) else null
    }

    /** Open events where the user holds money but has nothing of their own to settle in [mine]. */
    val holding: List<SettlingEvent> = active.filter { (event, settlement) ->
        settlement.status == EventStatus.OPEN && mine.none { it.event.id == event.id } && held(settlement) != null
    }

    /**
     * Everything still open, for leads: open, at a loss, or awaiting a fee once the gig is over (a booked gig with its
     * fee entered is not business yet).
     */
    val forLeads: List<SettlingEvent> = if (me?.role?.atLeast(Role.LEAD) == true) {
        active.filter { (event, s) ->
            s.status == EventStatus.OPEN || s.status == EventStatus.LOSS || (s.status == EventStatus.AWAITING && event.date <= today)
        }
    } else {
        emptyList()
    }

    val isEmpty: Boolean get() = mine.isEmpty() && holding.isEmpty() && forLeads.isEmpty()

    /** What the user holds that is their own, also in a pair: only what the pair still has to pass on counts (§7). */
    fun held(settlement: Settlement): Money? {
        val own = settlement.line(me?.id ?: "")?.delta?.takeIf { it.isPositive } ?: return null
        val pair = settlement.party(me!!.id)?.delta?.takeIf { it.isPositive } ?: return null
        return minOf(own, pair)
    }

    companion object {
        /**
         * A suggested transfer to the band fund that its holder (or their pair) would make to themselves: they keep the
         * money, nobody pays (I6 §8).
         */
        fun keepsForFund(band: Band, settlement: Settlement, suggestion: SuggestedTransfer): Boolean {
            val holders = settlement.party(suggestion.from)?.partyIds ?: listOf(suggestion.from)
            return suggestion.to == BAND_FUND && band.treasurerMemberId != null && band.treasurerMemberId in holders
        }
    }
}

/** Handing money over by hand in one event: who may give what to whom (I5 §4.6). */
class HandOver(private val view: BandView, private val event: Event, private val settlement: Settlement, private val can: Capabilities) {
    private val band get() = view.band

    /** Everyone who can take part in a transfer of this event: participants, plus the band fund if it is set up. */
    val parties: List<Id> = run {
        val lines = settlement.lines.map { it.partyId }.ifEmpty { SettlementCalculator.partyOrder(band, event) }
        lines + (if (band.treasurerMemberId != null && BAND_FUND !in lines) listOf(BAND_FUND) else emptyList())
    }

    /** Recipients the user may record a transfer to from [from]: not artists, who never hold money (I4 §1.1). */
    fun recipients(from: Id): List<Id> = parties.filter { it != from && !Artists.isArtist(band, it) && can.recordTransfer(event, from, it) }

    /**
     * What [partyId] can hand over: what they hold, or for an artist only what they were paid too much
     * (I4 §1.1), e.g. after a refund accepted later made their share smaller.
     */
    fun amount(partyId: Id): Money {
        val line = settlement.line(partyId) ?: return Money.ZERO
        return (if (Artists.isArtist(band, partyId)) line.delta else line.balance).takeIf { it.isPositive } ?: Money.ZERO
    }

    /** Parties holding money the user may record a hand-over from (themselves for members, anyone for leads). */
    val senders: List<Id> get() = parties.filter { amount(it).isPositive && recipients(it).isNotEmpty() }
}
