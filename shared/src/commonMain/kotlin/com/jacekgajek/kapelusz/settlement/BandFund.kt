package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.model.isOn
import com.jacekgajek.kapelusz.model.sum
import com.jacekgajek.kapelusz.model.sumOfMoney

/** Band fund ("Kasa zespołowa") totals. */
object BandFund {

    /**
     * Money the fund actually has: recorded event transfers to/from the fund, fees paid straight to it and manual entries.
     */
    fun balance(band: Band): Money = transferredFromEvents(band) + band.events.sumOfMoney(::heldBy) + band.fundEntries.sumOfMoney { it.amount }

    /** Fund amounts promised by events whose transfers are not recorded yet. */
    fun notHandedOver(band: Band): Money = band.events.sumOfMoney { it.fund - heldBy(it) } - transferredFromEvents(band)

    /**
     * The fund may be turned off only when it holds nothing and no event still owes it money or expects money from it.
     * Checked per event: one event owing 100 and another expecting 100 do not cancel out.
     */
    fun canDisable(band: Band): Boolean = balance(band).isZero && band.events.all { isHandedOver(it) }

    /** The event's fund amount has been transferred to (or, if negative, from) the fund in full. */
    fun isHandedOver(event: Event): Boolean = moved(event) == event.fund

    /** Events a changed fund default can still be applied to: not archived, not settled. */
    fun canApplyDefault(view: SettledEvents, event: Event): Boolean = !event.archived && !view.isSettled(event)

    /** Events that still have the band's fund default, to offer a new one to (as a member's default share is). */
    fun stillDefault(view: SettledEvents): List<Event> = view.band.events.filter { canApplyDefault(view, it) && view.band.fundDefault.isOn(it) }

    /** Money the event has actually moved into the fund so far (negative: out of it): transfers and fees paid to it. */
    fun moved(event: Event): Money = transferred(event.transfers) + heldBy(event)

    /** A fee paid straight to the band's account: the fund collected it for the event. */
    private fun heldBy(event: Event): Money = event.holdings.filter { it.partyId == BAND_FUND }.sumOfMoney { it.amount }

    private fun transferred(transfers: List<Transfer>): Money = transfers.map {
        when (BAND_FUND) {
            it.to -> it.amount
            it.from -> -it.amount
            else -> Money.ZERO
        }
    }.sum()

    private fun transferredFromEvents(band: Band): Money = band.events.flatMap { it.transfers }.map {
        when (BAND_FUND) {
            it.to -> it.amount
            it.from -> -it.amount
            else -> Money.ZERO
        }
    }.sum()
}
