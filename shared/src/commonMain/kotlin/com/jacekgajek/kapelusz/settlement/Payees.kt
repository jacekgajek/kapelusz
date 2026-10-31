package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money

/**
 * Where to send money: a member's own BLIK number and bank account, or the band fund's
 * ([member] null), whichever were given.
 */
data class Payee(val member: Member?, val blikPhone: String?, val bankAccount: String?)

object Payees {
    /**
     * Who to send a suggested transfer to: the band fund's own details for the fund (never its holder's); for a pair, the partner it is
     * recorded to (owed more) first, then the other. With [payer], the one that person pays comes first (a paying pair's part may go to
     * each partner).
     */
    fun forTransfer(band: Band, settlement: Settlement, from: Id, to: Id, amount: Money, payer: Id? = null): List<Payee> {
        if (to == BAND_FUND) return listOfNotNull(fund(band))
        val parts = SettleTogether.split(band, settlement, from, to, amount)
        val first = (parts.firstOrNull { it.from == payer } ?: parts.firstOrNull())?.to ?: to
        // Each partner, with details or not: the first is the one it is recorded to, even with nothing to show for them.
        return listOfNotNull(first, to, settlement.partners[to]).distinct().mapNotNull { band.member(it) }
            .map { Payee(it, it.blikPhone, it.bankAccount) }
    }

    /** Who to send a hand-over to [to]: that member, or the band fund; empty without a number or an account. */
    fun forHandOver(band: Band, to: Id): List<Payee> = listOfNotNull(if (to == BAND_FUND) fund(band) else band.member(to)?.let(::member))

    /** The band fund's own details, null without any (or with the fund off). */
    fun fund(band: Band): Payee? = Payee(null, band.fundBlikPhone, band.fundBankAccount)
        .takeIf { band.treasurerMemberId != null && (it.blikPhone != null || it.bankAccount != null) }

    private fun member(member: Member): Payee? = Payee(member, member.blikPhone, member.bankAccount).takeIf { it.blikPhone != null || it.bankAccount != null }
}
