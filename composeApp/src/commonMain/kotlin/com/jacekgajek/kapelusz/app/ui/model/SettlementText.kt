package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.PartyLine
import com.jacekgajek.kapelusz.domain.Settlement
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import kotlinx.datetime.LocalDate

/**
 * The words of the shared settlement in the sender's language, from `strings_share.xml` ("%1$s" etc. are filled in
 * here). Passed in rather than read here, so [settlementText] stays a plain function that tests can call.
 */
data class SettlementWords(
    val fundLabel: String,
    val income: String,
    val refunds: String,
    val toFund: String,
    val fromFund: String,
    /** The heading above everyone's share, one a line. */
    val shares: String,
    val shareWithRefunds: String,
    /** "%1$s" filled with the amount: a fixed share (ITERATION_3 §2.4). */
    val fixed: String,
    /** "%1$s" the amount, "%2$s" the percentage: a percentage of the profit (ITERATION_7 §6). */
    val percent: String = "%1\$s (%2\$s)",
    val done: String,
    val stillToPay: String,
    val allSettled: String,
    /** Instead of "Still to pay" while the event has a problem to sort out first (a loss, no shares, no treasurer). */
    val incomplete: String,
    val footer: String,
    /** "%1$s" filled with what the organiser still owes (ITERATION_4 §2.2). */
    val awaited: String = "",
    /** Closes the text while the organiser still owes part of the income. */
    val awaitingRest: String = "",
    /** "%1$s and %2$s": a pair settling together (ITERATION_5 §7). */
    val pair: String = "%1\$s & %2\$s",
)

/**
 * A settlement as plain text to send to the band (ITERATION_3 §2.1): the event, the totals, everyone's share, then the
 * recorded transfers ("Done") and the open suggestions ("Still to pay"), or "All settled", or that it is incomplete; a footer names the app.
 * Each of these a block of its own, one item a line.
 */
fun settlementText(band: Band, event: Event, settlement: Settlement, language: String, words: SettlementWords): String {
    fun money(amount: Money) = formatMoney(amount, band.currency, language)
    fun name(partyId: Id) = partyName(band, event, partyId, words.fundLabel)
    fun transfer(from: Id, to: Id, amount: Money) = "${name(from)} → ${name(to)}: ${money(amount)}"

    // A suggestion is between parties: a pair settling together by both names (ITERATION_5 §7).
    fun party(partyId: Id) = partyName(band, event, settlement, partyId, words.fundLabel, words.pair)
    // Blocks apart by an empty line, one item a line: easy to read in a chat.
    val blocks = mutableListOf<List<String>>()
    blocks.add(listOf("${event.name} · ${formatShortDate(event.date, language)}"))
    blocks.add(
        listOfNotNull(
            fill(words.income, money(settlement.income)),
            fill(words.refunds, money(settlement.refundsTotal)).takeUnless { settlement.refundsTotal.isZero },
            when {
                settlement.fundAmount.isPositive -> fill(words.toFund, money(settlement.fundAmount))
                settlement.fundAmount.isNegative -> fill(words.fromFund, money(-settlement.fundAmount))
                else -> null
            },
            fill(words.awaited, money(event.awaited)).takeIf { event.awaited.isPositive },
        ),
    )
    val shares = settlement.lines.filter { it.partyId != BAND_FUND }.map { shareText(it, name(it.partyId), ::money, words) }
    if (shares.isNotEmpty()) blocks.add(listOf(words.shares) + shares.mapIndexed { index, share -> if (index < shares.lastIndex) "$share," else share })
    if (event.transfers.isNotEmpty()) blocks.add(listOf(words.done) + event.transfers.map { transfer(it.from, it.to, it.amount) })
    blocks.add(
        buildList {
            if (settlement.status == EventStatus.SETTLED) {
                add(words.allSettled)
            } else if (settlement.problems.isNotEmpty()) {
                add(words.incomplete)
            } else if (settlement.suggestions.isNotEmpty()) {
                add(words.stillToPay)
                settlement.suggestions.forEach { add("${party(it.from)} → ${party(it.to)}: ${money(it.amount)}") }
            }
        },
    )
    // A closing sentence, apart from the transfers above it.
    if (event.awaited.isPositive && settlement.problems.isEmpty()) blocks.add(listOf(words.awaitingRest))
    blocks.add(listOf(words.footer))
    return blocks.filter { it.isNotEmpty() }.joinToString("\n\n") { it.joinToString("\n") }
}

/** One party's share, "Jacek 475 zł (+ 150 zł refunds)": kept apart so a fixed amount (§2.4) or a percentage can be worded here. */
private fun shareText(line: PartyLine, name: String, money: (Money) -> String, words: SettlementWords): String {
    val share = when {
        line.isFixed -> fill(words.fixed, money(line.share))
        line.percent != null -> fill(words.percent, money(line.share), "${line.percent}%")
        else -> money(line.share)
    }
    return if (line.refunds.isZero) "$name $share" else fill(words.shareWithRefunds, name, share, money(line.refunds))
}

/** Fills "%1$s", "%2$s"… of a string resource read without arguments. */
private fun fill(template: String, vararg args: String): String = args.foldIndexed(template) { index, text, arg -> text.replace("%${index + 1}\$s", arg) }

private val EN_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val PL_DAYS = listOf("pon.", "wt.", "śr.", "czw.", "pt.", "sob.", "niedz.")
private val EN_SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** `Sat 12 Sep` in English, `sob. 12.09` in Polish. */
fun formatShortDate(date: LocalDate, language: String): String {
    val day = date.dayOfWeek.ordinal
    return if (language == "pl") {
        "${PL_DAYS[day]} ${date.day.toString().padStart(2, '0')}.${(date.month.ordinal + 1).toString().padStart(2, '0')}"
    } else {
        "${EN_DAYS[day]} ${date.day} ${EN_SHORT_MONTHS[date.month.ordinal]}"
    }
}
