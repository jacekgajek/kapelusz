package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.ui.model.summaryPeriod
import com.jacekgajek.kapelusz.app.ui.model.withSummaryPeriod
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.view.SummaryPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

class SummaryPeriodsTest {
    @Test
    fun periodsAreRememberedPerBandAndAnUnreadableOneIsForgotten() {
        val custom = SummaryPeriod.Custom(LocalDate(2025, 9, 1), LocalDate(2026, 8, 31))
        val settings = AppSettings().withSummaryPeriod("b", custom)
        val stored = KapeluszJson.decodeFromString(AppSettings.serializer(), KapeluszJson.encodeToString(AppSettings.serializer(), settings))
        assertEquals(custom, stored.summaryPeriod("b"))
        assertEquals(SummaryPeriod.Last12Months, stored.summaryPeriod("other"))
        assertEquals(SummaryPeriod.Last12Months, AppSettings(summaryPeriods = mapOf("b" to "{\"type\":\"season\"}")).summaryPeriod("b"))
    }
}
