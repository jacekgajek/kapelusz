package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money

/**
 * A member's BLIK phone number (ITERATION_5 §8): any phone number, 7 to 15 digits with an optional leading `+`. A
 * Polish one (9 digits, or +48 and 9 digits) is shown in threes and copied without +48, as Polish bank apps take it.
 */
object BlikPhone {
    private const val MIN_DIGITS = 7
    private const val MAX_DIGITS = 15

    /**
     * [input] as stored: spaces of any kind, dashes, dots, brackets and invisible marks (as contact apps add on copy)
     * dropped, `00` before a country code as `+`; null if not a number. A country code never starts with 0.
     */
    fun normalize(input: String): String? {
        val compact = input.filterNot {
            it.isWhitespace() || it == '.' || it == '(' || it == ')' ||
                it.category == CharCategory.DASH_PUNCTUATION || it.category == CharCategory.FORMAT || it == '\u00A0' || it == '\u202F'
        }
        val plus = compact.startsWith("+") || compact.startsWith("00")
        val digits = when {
            compact.startsWith("+") -> compact.drop(1)
            compact.startsWith("00") -> compact.drop(2)
            else -> compact
        }
        if (digits.length !in MIN_DIGITS..MAX_DIGITS || !digits.all { it in '0'..'9' }) return null
        if (plus && digits.startsWith("0")) return null
        return if (plus) "+$digits" else digits
    }

    /** The 9 digits of a Polish number, else null. */
    private fun polish(stored: String): String? = when {
        stored.length == 9 && !stored.startsWith("+") -> stored
        stored.startsWith("+48") && stored.length == 12 -> stored.drop(3)
        else -> null
    }

    /** For reading: `600 123 456` for a Polish number, any other as stored. */
    fun display(stored: String): String = polish(stored)?.chunked(3)?.joinToString(" ") ?: stored

    /** For pasting into a bank app: a Polish number's 9 digits, any other as stored. */
    fun copyText(stored: String): String = polish(stored) ?: stored

    /**
     * Who to send a suggested transfer to by BLIK, with a number given: the band fund's holder for the fund; for a pair,
     * the partner it is recorded to (owed more) first, then the other (ITERATION_5 §8). With [payer], the one that
     * person pays comes first (a paying pair's part may go to each partner).
     */
    fun recipients(band: Band, settlement: Settlement, from: Id, to: Id, amount: Money, payer: Id? = null): List<Member> {
        val people = if (to == BAND_FUND) {
            listOfNotNull(band.treasurerMemberId)
        } else {
            val parts = SettleTogether.split(band, settlement, from, to, amount)
            val first = (parts.firstOrNull { it.from == payer } ?: parts.firstOrNull())?.to ?: to
            listOfNotNull(first, to, settlement.partners[to]).distinct()
        }
        return people.mapNotNull { band.member(it) }.filter { it.blikPhone != null }
    }

    /** Who to send a hand-over to [to] by BLIK: that member, or the band fund's holder; empty without a number. */
    fun recipient(band: Band, to: Id): List<Member> =
        listOfNotNull(band.member(if (to == BAND_FUND) band.treasurerMemberId ?: return emptyList() else to)).filter { it.blikPhone != null }
}
