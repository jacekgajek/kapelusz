package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.view.SummaryPeriod

/** The period [bandId]'s summary was last shown for; at first, or unreadable: the last 12 months. */
fun AppSettings.summaryPeriod(bandId: String): SummaryPeriod = summaryPeriods[bandId]?.let { runCatching { KapeluszJson.decodeFromString(SummaryPeriod.serializer(), it) }.getOrNull() }
    ?: SummaryPeriod.Last12Months

fun AppSettings.withSummaryPeriod(bandId: String, period: SummaryPeriod): AppSettings = copy(summaryPeriods = summaryPeriods + (bandId to KapeluszJson.encodeToString(SummaryPeriod.serializer(), period)))
