package com.jacekgajek.kapelusz.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** ITERATION_4 §2.4: each event opens on the tab last used there. */
class EventTabTest {

    @Test
    fun theTabIsRememberedPerEvent() {
        val settings = AppSettings().withEventTab("b", "gig", EventTab.MONEY)
        assertEquals(EventTab.MONEY, settings.eventTab("b", "gig"))
        assertEquals(EventTab.GIG, settings.eventTab("b", "other"), "another event: Gig")
        assertEquals(EventTab.GIG, settings.eventTab("other-band", "gig"), "the same id in another band")
        assertEquals(EventTab.GIG, settings.withEventTab("b", "gig", EventTab.GIG).eventTab("b", "gig"))
        assertEquals(EventTab.SETTLEMENT, settings.withEventTab("b", "gig", EventTab.SETTLEMENT).eventTab("b", "gig"))
    }

    @Test
    fun onlyTheMostRecentAreKept() {
        val many = (0..AppSettings.MAX_EVENT_TABS).fold(AppSettings()) { settings, i -> settings.withEventTab("b", "e$i", EventTab.MONEY) }
        assertEquals(AppSettings.MAX_EVENT_TABS, many.eventTabs.size)
        assertEquals(EventTab.GIG, many.eventTab("b", "e0"), "the oldest dropped")
        assertEquals(EventTab.MONEY, many.eventTab("b", "e${AppSettings.MAX_EVENT_TABS}"))
    }
}
