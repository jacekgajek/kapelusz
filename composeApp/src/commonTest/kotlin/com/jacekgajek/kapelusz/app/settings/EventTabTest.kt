package com.jacekgajek.kapelusz.app.settings

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** Each event opens on the tab last used there. */
class EventTabTest :
    ExpectSpec({
        expect("the tab is remembered per event") {
            val settings = AppSettings().withEventTab("b", "gig", EventTab.MONEY)
            settings.eventTab("b", "gig") shouldBe EventTab.MONEY
            withClue("another event: Gig") { settings.eventTab("b", "other") shouldBe EventTab.GIG }
            withClue("the same id in another band") { settings.eventTab("other-band", "gig") shouldBe EventTab.GIG }
            settings.withEventTab("b", "gig", EventTab.GIG).eventTab("b", "gig") shouldBe EventTab.GIG
            settings.withEventTab("b", "gig", EventTab.SETTLEMENT).eventTab("b", "gig") shouldBe EventTab.SETTLEMENT
        }

        expect("only the most recent are kept") {
            val many = (0..AppSettings.MAX_EVENT_TABS).fold(AppSettings()) { settings, i ->
                settings.withEventTab("b", "e$i", EventTab.MONEY)
            }
            many.eventTabs.size shouldBe AppSettings.MAX_EVENT_TABS
            withClue("the oldest dropped") { many.eventTab("b", "e0") shouldBe EventTab.GIG }
            many.eventTab("b", "e${AppSettings.MAX_EVENT_TABS}") shouldBe EventTab.MONEY
        }
    })
