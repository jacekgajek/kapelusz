package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sum
import com.jacekgajek.kapelusz.model.sumOfMoney

/** Band fund ("Kasa zespołowa") totals. */
object BandFund {

    /**
     * Money the fund actually has: recorded event transfers to/from the fund, fees paid straight to it (ITERATION_4
     * §2.2) and manual entries.
     */
    fun balance(band: Band): Money = transferredFromEvents(band) + band.events.sumOfMoney(::heldBy) + band.fundEntries.sumOfMoney { it.amount }

    /** Fund amounts promised by events whose transfers are not recorded yet. */
    fun notHandedOver(band: Band): Money = band.events.sumOfMoney { it.fundAmount - heldBy(it) } - transferredFromEvents(band)

    /**
     * The fund may be turned off only when it holds nothing and no event still owes it money or expects money from it.
     * Checked per event: one event owing 100 and another expecting 100 do not cancel out.
     */
    fun canDisable(band: Band): Boolean = balance(band).isZero && band.events.all { isHandedOver(it) }

    /** The event's fund amount has been transferred to (or, if negative, from) the fund in full. */
    fun isHandedOver(event: Event): Boolean = transferred(event.transfers) + heldBy(event) == event.fundAmount

    /** A fee paid straight to the band's account: the fund collected it for the event (ITERATION_4 §2.2). */
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
