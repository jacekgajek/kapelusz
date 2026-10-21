package com.jacekgajek.artsplit.domain

import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.sum
import com.jacekgajek.artsplit.model.sumOfMoney

/** Band fund ("Kasa zespołowa") totals. */
object BandFund {

    /** Money the fund actually has: recorded event transfers to/from the fund plus manual entries. */
    fun balance(band: Band): Money = transferredFromEvents(band) + band.fundEntries.sumOfMoney { it.amount }

    /** Fund amounts promised by events whose transfers are not recorded yet. */
    fun notHandedOver(band: Band): Money = band.events.sumOfMoney { it.fundAmount } - transferredFromEvents(band)

    private fun transferredFromEvents(band: Band): Money = band.events.flatMap { it.transfers }.map {
        when (BAND_FUND) {
            it.to -> it.amount
            it.from -> -it.amount
            else -> Money.ZERO
        }
    }.sum()
}
