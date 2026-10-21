package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.domain.Places
import com.jacekgajek.artsplit.domain.SharedPlace
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.RejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlacesTest {
    @Test
    fun readsWhatGoogleMapsShares() {
        assertEquals(SharedPlace("Klub Stodoła", "https://maps.app.goo.gl/abc123"), Places.parse("Klub Stodoła\nhttps://maps.app.goo.gl/abc123"))
        assertEquals(SharedPlace(null, "https://www.google.com/maps/place/Warsaw/@52.2,21.0,12z"), Places.parse("https://www.google.com/maps/place/Warsaw/@52.2,21.0,12z"))
        assertEquals("https://maps.google.com/?q=52.23,21.01", Places.parse("See maps.google.com: https://maps.google.com/?q=52.23,21.01.")?.url)
        assertNull(Places.parse("Klub Stodoła, Warszawa"))
        assertNull(Places.parse("https://example.com/maps"))
    }

    @Test
    fun linksToOpen() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=Klub%20Stodo%C5%82a", Places.searchUrl("Klub Stodoła"))
        assertEquals("https://www.google.com/maps", Places.searchUrl(" "))
        val base = Event("e", "Gig", DATE, location = "Klub Stodoła, Warszawa")
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Klub%20Stodo%C5%82a%2C%20Warszawa", Places.navigateUrl(base))
        assertEquals("https://maps.app.goo.gl/x", Places.navigateUrl(base.copy(mapUrl = "https://maps.app.goo.gl/x")))
        assertNull(Places.navigateUrl(base.copy(location = null)))
    }

    @Test
    fun onlySafeLinksAreStored() {
        val scenario = Scenario("Alice")
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(CreateEvent("e", "Gig", DATE, mapUrl = "javascript:alert(1)")).reason)
        scenario.apply(CreateEvent("e", "Gig", DATE, mapUrl = " https://maps.app.goo.gl/x "))
        assertEquals("https://maps.app.goo.gl/x", scenario.band.event("e")!!.mapUrl)
    }
}
