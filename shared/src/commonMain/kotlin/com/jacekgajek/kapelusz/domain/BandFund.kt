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

    /** Money the fund actually has: recorded event transfers to/from the fund plus manual entries. */
    fun balance(band: Band): Money = transferredFromEvents(band) + band.fundEntries.sumOfMoney { it.amount }

    /** Fund amounts promised by events whose transfers are not recorded yet. */
    fun notHandedOver(band: Band): Money = band.events.sumOfMoney { it.fundAmount } - transferredFromEvents(band)

    /**
     * The fund may be turned off only when it holds nothing and no event still owes it money or expects money from it.
     * Checked per event: one event owing 100 and another expecting 100 do not cancel out.
     */
    fun canDisable(band: Band): Boolean = balance(band).isZero && band.events.all { isHandedOver(it) }

    /** The event's fund amount has been transferred to (or, if negative, from) the fund in full. */
    fun isHandedOver(event: Event): Boolean = transferred(event.transfers) == event.fundAmount

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
