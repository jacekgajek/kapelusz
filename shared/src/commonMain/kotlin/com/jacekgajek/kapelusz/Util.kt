package com.jacekgajek.kapelusz

import kotlinx.datetime.LocalDate

fun dateFilter(date: LocalDate, from: LocalDate?, to: LocalDate?): Boolean = if (from != null && to != null) {
    date in from..to
} else if (from != null) {
    date >= from
} else if (to != null) {
    date <= to
} else {
    true
}
