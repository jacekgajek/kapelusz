package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.domain.Places
import com.jacekgajek.artsplit.domain.SharedPlace
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.UpdateEvent
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
        assertEquals("https://www.google.pl/maps/place/X", Places.parse("https://www.google.pl/maps/place/X")?.url)
        assertEquals("https://goo.gl/maps/abc", Places.parse("https://goo.gl/maps/abc")?.url)
        // Look-alike hosts are not Google Maps.
        for (spoof in listOf("https://google.evil.io/maps/x", "https://www.google.com.evil.io/maps", "https://maps.app.goo.gl.evil.io/x", "https://maps.app.goo.gl@evil.io/x", "https://goo.gl/abc", "https://www.google.com/search?q=maps")) {
            assertNull(Places.parse(spoof), spoof)
        }
        assertEquals("https://maps.app.goo.gl/real", Places.parse("https://google.evil.io/maps/x and https://maps.app.goo.gl/real")?.url, "the first real Maps link")
        assertNull(Places.parse("https://www.google.com/maps/place/" + "x".repeat(Places.MAX_URL)), "too long to store")
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
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(UpdateEvent("e", mapUrl = Change("https://maps.app.goo.gl/\"><x"))).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(UpdateEvent("e", mapUrl = Change("https://x/" + "a".repeat(Places.MAX_URL)))).reason)
    }

    @Test
    fun theLinkFollowsTheLocation() {
        val scenario = Scenario("Alice")
        scenario.apply(CreateEvent("e", "Gig", DATE, location = "Klub", mapUrl = "https://maps.app.goo.gl/x"))
        scenario.apply(UpdateEvent("e", name = Change("Big gig")))
        assertEquals("https://maps.app.goo.gl/x", scenario.band.event("e")!!.mapUrl, "unchanged when not mentioned")
        scenario.apply(UpdateEvent("e", mapUrl = Change("https://maps.app.goo.gl/y")))
        assertEquals("https://maps.app.goo.gl/y", scenario.band.event("e")!!.mapUrl)
        scenario.apply(UpdateEvent("e", location = Change("Klub 2")))
        assertEquals("https://maps.app.goo.gl/y", scenario.band.event("e")!!.mapUrl, "a typo fix keeps the pin")
        scenario.apply(UpdateEvent("e", location = Change(null)))
        assertNull(scenario.band.event("e")!!.mapUrl, "no location, no pin (also for apps that don't know the link)")
        scenario.apply(UpdateEvent("e", location = Change("Klub"), mapUrl = Change("https://maps.app.goo.gl/z")))
        scenario.apply(UpdateEvent("e", mapUrl = Change(null)))
        assertEquals("Klub", scenario.band.event("e")!!.location)
        assertNull(scenario.band.event("e")!!.mapUrl)
    }
}
