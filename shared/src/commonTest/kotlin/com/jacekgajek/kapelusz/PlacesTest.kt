package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.domain.SharedPlace
import com.jacekgajek.kapelusz.domain.MapTarget
import com.jacekgajek.kapelusz.model.Event
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
    fun navigateGoesToTheLocationText() {
        val base = Event("e", "Gig", DATE, location = "Klub Stodoła, Warszawa")
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Klub%20Stodo%C5%82a%2C%20Warszawa", Places.navigateUrl(base))
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=52.229700%2C%2021.012200", Places.navigateUrl(base.copy(location = "52.229700, 21.012200")))
        assertNull(Places.navigateUrl(base.copy(location = null)))
    }

    @Test
    fun aPlaceIsItsNameAndFullAddressWithoutTheCountry() {
        assertEquals("Ołówek, Legnicka 36, 53-673 Wrocław", Places.placeText("Ołówek", "Legnicka 36, 53-673 Wrocław, Polska", "Polska"))
        assertEquals("Legnicka 36, 53-673 Wrocław", Places.placeText("Legnicka 36", "Legnicka 36, 53-673 Wrocław, Polska", "Polska"), "an address picked: not twice")
        assertEquals("Ołówek", Places.placeText("Ołówek", null, null))
        assertEquals("Ryman, 116 5th Ave N, Nashville, TN 37219, USA", Places.placeText("Ryman", "116 5th Ave N, Nashville, TN 37219, USA", null))
    }

    @Test
    fun readsWhereAFullMapsLinkPoints() {
        val place = "https://www.google.com/maps/place/Klub+Stodo%C5%82a/@52.2121,21.0055,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d52.2119581!4d21.0062814!16s%2Fg%2F1?entry=tts"
        assertEquals(MapTarget("Klub Stodoła", "52.2119581" to "21.0062814", null), Places.target(place), "the place's own pin, not the map's centre")
        assertEquals(MapTarget("Warsaw", "52.2" to "21.0", null), Places.target("https://www.google.com/maps/place/Warsaw/@52.2,21.0,12z"))
        val dropped = "https://www.google.com/maps/place/52%C2%B013'47.0%22N+21%C2%B000'43.9%22E/@52.2297,21.0122,17z/data=!3m1!4b1!4m4!3m3!8m2!3d52.229722!4d21.012194"
        assertEquals(MapTarget(null, "52.229722" to "21.012194", null), Places.target(dropped), "a dropped pin has no name")
        assertEquals(MapTarget(null, "52.23" to "21.01", null), Places.target("https://maps.google.com/?q=52.23,21.01"))
        assertEquals(MapTarget(null, null, "Hala Stulecia Wrocław"), Places.target("https://www.google.com/maps/search/?api=1&query=Hala+Stulecia+Wroc%C5%82aw"))
        assertEquals(MapTarget(null, null, "Ołówek"), Places.target("https://www.google.pl/maps?q=O%C5%82%C3%B3wek&ll=51.1,17.0"), "the searched text; ll is only the map's centre")
        assertEquals(MapTarget(null, null, "pizza"), Places.target("https://www.google.com/maps/search/pizza/@52.2,21.0,14z"), "a search, not a place at the map's centre")
        assertEquals(MapTarget(null, null, "Hala Stulecia, Wrocław"), Places.target("https://www.google.com/maps/dir/Home/Hala+Stulecia,+Wroc%C5%82aw/@51.1,17.0,12z/data=!4m8!1d17.07!2d51.10"), "directions: the destination")
        assertEquals(MapTarget("100% Club 🎸", null, null), Places.target("https://www.google.com/maps/place/100%25+Club+%F0%9F%8E%B8"))
        assertEquals(MapTarget("A%-1B", null, null), Places.target("https://www.google.com/maps/place/A%-1B"), "a malformed escape kept as it is")
        assertNull(Places.target("https://maps.app.goo.gl/abc"), "a short link is followed first")
        assertNull(Places.target("https://example.com/maps/place/X/@1.0,2.0"))
    }

    @Test
    fun aPickedPlaceIsItsNameAndTownWithItsExactPin() {
        val stodola = PlaceSuggestion("ChIJ abc", "Stodoła", "Stefana Batorego 10, 02-591 Warszawa, Polska")
        assertEquals("Stodoła, Warszawa", Places.label(stodola), "the postal code is dropped")
        assertEquals("Warszawa", Places.label(PlaceSuggestion("p", "Warszawa", "Polska")), "a town alone")
        assertEquals("Hala Stulecia", Places.label(PlaceSuggestion("p", "Hala Stulecia", null)))
        assertEquals("Klub Wrocław", Places.label(PlaceSuggestion("p", "Klub Wrocław", "Wrocław, Polska")), "the town already in the name")
        assertEquals("Ryman Auditorium, Nashville", Places.label(PlaceSuggestion("p", "Ryman Auditorium", "Broadway, Nashville, TN, USA")), "not the state")
        assertEquals("The Venue, London", Places.label(PlaceSuggestion("p", "The Venue", "1 High St, London SW1A 1AA, UK")))

    }
}
