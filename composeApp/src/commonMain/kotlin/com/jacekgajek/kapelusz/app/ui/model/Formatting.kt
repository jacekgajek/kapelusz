package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.model.Money
import kotlin.math.absoluteValue
import kotlinx.datetime.LocalDate

/** Locale-aware money text: `1 000,00 zł` in Polish, `1,000.00 PLN` in English. */
fun formatMoney(money: Money, currency: String, language: String): String {
    val polish = language == "pl"
    val abs = money.minor.absoluteValue
    val whole = (abs / 100).toString().reversed().chunked(3).joinToString(if (polish) " " else ",").reversed()
    val fraction = (abs % 100).toString().padStart(2, '0')
    val sign = if (money.isNegative) "−" else ""
    val symbol = if (polish && currency == "PLN") "zł" else currency
    return "$sign$whole${if (polish) "," else "."}$fraction $symbol"
}

/** Amount as typed in a text field, e.g. `129,60` in Polish. */
fun formatMoneyInput(money: Money, language: String): String = money.toString().let { if (language == "pl") it.replace('.', ',') else it }

private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** `13.09.2026` in Polish, `13 Sep 2026` in English. */
fun formatDate(date: LocalDate, language: String): String {
    val day = date.day.toString().padStart(2, '0')
    return if (language == "pl") {
        "$day.${date.month.ordinal.plus(1).toString().padStart(2, '0')}.${date.year}"
    } else {
        "$day ${EN_MONTHS[date.month.ordinal]} ${date.year}"
    }
}

private val PL_MONTHS = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")

/** A month in three letters, as on a calendar page: `Nov` / `lis`. */
fun formatMonthShort(date: LocalDate, language: String): String = (if (language == "pl") PL_MONTHS else EN_MONTHS)[date.month.ordinal]

private val EN_MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val PL_MONTH_NAMES = listOf("styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień")

/** `2026-10` as `October 2026` / `październik 2026`. */
fun formatMonth(yearMonth: String, language: String): String {
    val (year, month) = yearMonth.split('-').map { it.toInt() }
    val names = if (language == "pl") PL_MONTH_NAMES else EN_MONTH_NAMES
    return "${names[month - 1]} $year"
}
