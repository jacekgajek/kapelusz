package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.settings.AppSettings
import com.jacekgajek.kapelusz.app.settings.summaryPeriod
import com.jacekgajek.kapelusz.app.settings.withSummaryPeriod
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.view.SummaryPeriod
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

class SummaryPeriodsTest :
    ExpectSpec({
        expect("periods are remembered per band and an unreadable one is forgotten") {
            val custom = SummaryPeriod.Custom(LocalDate(2025, 9, 1), LocalDate(2026, 8, 31))
            val settings = AppSettings().withSummaryPeriod("b", custom)
            val json = KapeluszJson.encodeToString(AppSettings.serializer(), settings)
            val stored = KapeluszJson.decodeFromString(AppSettings.serializer(), json)
            stored.summaryPeriod("b") shouldBe custom
            stored.summaryPeriod("other") shouldBe SummaryPeriod.Last12Months
            AppSettings(summaryPeriods = mapOf("b" to "{\"type\":\"season\"}")).summaryPeriod("b") shouldBe SummaryPeriod.Last12Months
        }
    })
