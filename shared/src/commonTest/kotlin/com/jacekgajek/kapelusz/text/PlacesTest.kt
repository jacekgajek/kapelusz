package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.text.MapTarget
import com.jacekgajek.kapelusz.text.Places
import com.jacekgajek.kapelusz.text.SharedPlace
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PlacesTest :
    ExpectSpec({
        expect("reads what Google Maps shares") {
            Places.parse("Klub Stodoła\nhttps://maps.app.goo.gl/abc123") shouldBe
                SharedPlace("Klub Stodoła", "https://maps.app.goo.gl/abc123")
            Places.parse("https://www.google.com/maps/place/Warsaw/@52.2,21.0,12z") shouldBe
                SharedPlace(null, "https://www.google.com/maps/place/Warsaw/@52.2,21.0,12z")
            Places.parse("See maps.google.com: https://maps.google.com/?q=52.23,21.01.")?.url shouldBe
                "https://maps.google.com/?q=52.23,21.01"
            Places.parse("Klub Stodoła, Warszawa").shouldBeNull()
            Places.parse("https://example.com/maps").shouldBeNull()
            Places.parse("https://www.google.pl/maps/place/X")?.url shouldBe "https://www.google.pl/maps/place/X"
            Places.parse("https://goo.gl/maps/abc")?.url shouldBe "https://goo.gl/maps/abc"
            // Look-alike hosts are not Google Maps.
            val spoofs = listOf(
                "https://google.evil.io/maps/x",
                "https://www.google.com.evil.io/maps",
                "https://maps.app.goo.gl.evil.io/x",
                "https://maps.app.goo.gl@evil.io/x",
                "https://goo.gl/abc",
                "https://www.google.com/search?q=maps",
            )
            for (spoof in spoofs) {
                withClue(spoof) { Places.parse(spoof).shouldBeNull() }
            }
            withClue("the first real Maps link") {
                Places.parse("https://google.evil.io/maps/x and https://maps.app.goo.gl/real")?.url shouldBe "https://maps.app.goo.gl/real"
            }
            withClue("too long to store") {
                Places.parse("https://www.google.com/maps/place/" + "x".repeat(Places.MAX_URL)).shouldBeNull()
            }
        }

        expect("a pin is written with six decimals and read back") {
            Places.pinText(51.10912, 17.03257) shouldBe "51.109120, 17.032570"
            Places.pinText(-0.0000014, -179.5) shouldBe "-0.000001, -179.500000"
            Places.pinText(51.0, 17.0, "Leśniczówka") shouldBe "Leśniczówka, 51.000000, 17.000000"
            Places.pinIn("Leśniczówka, 51.109120, 17.032570") shouldBe (51.10912 to 17.03257)
            Places.pinIn("Ołówek, Legnicka 36, 53-673 Wrocław").shouldBeNull()
            withClue("not on Earth") { Places.pinIn("95.0, 17.0").shouldBeNull() }
            withClue("not part of a longer number") { Places.pinIn("Hala 123.45, 17.5").shouldBeNull() }
            Places.pinText(51.0, 17.0, "Leśniczówka\nNowa ") shouldBe "Leśniczówka Nowa, 51.000000, 17.000000"
        }

        expect("navigate goes to the location text") {
            val base = Event("e", "Gig", DATE, location = "Klub Stodoła, Warszawa")
            Places.navigateUrl(base) shouldBe "https://www.google.com/maps/dir/?api=1&destination=Klub%20Stodo%C5%82a%2C%20Warszawa"
            Places.navigateUrl(base.copy(location = "52.229700, 21.012200")) shouldBe
                "https://www.google.com/maps/dir/?api=1&destination=52.229700%2C%2021.012200"
            Places.navigateUrl(base.copy(location = null)).shouldBeNull()
        }

        expect("a place is its name and full address without the country") {
            Places.placeText("Ołówek", "Legnicka 36, 53-673 Wrocław, Polska", "Polska") shouldBe "Ołówek, Legnicka 36, 53-673 Wrocław"
            withClue("an address picked: not twice") {
                Places.placeText("Legnicka 36", "Legnicka 36, 53-673 Wrocław, Polska", "Polska") shouldBe "Legnicka 36, 53-673 Wrocław"
            }
            Places.placeText("Ołówek", null, null) shouldBe "Ołówek"
            Places.placeText("Ryman", "116 5th Ave N, Nashville, TN 37219, USA", null) shouldBe
                "Ryman, 116 5th Ave N, Nashville, TN 37219, USA"
        }

        expect("reads where a full Maps link points") {
            val place = "https://www.google.com/maps/place/Klub+Stodo%C5%82a/@52.2121,21.0055,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d52.2119581!4d21.0062814!16s%2Fg%2F1?entry=tts"
            withClue("the place's own pin, not the map's centre") {
                Places.target(place) shouldBe MapTarget("Klub Stodoła", "52.2119581" to "21.0062814", null)
            }
            Places.target("https://www.google.com/maps/place/Warsaw/@52.2,21.0,12z") shouldBe MapTarget("Warsaw", "52.2" to "21.0", null)
            val dropped = "https://www.google.com/maps/place/52%C2%B013'47.0%22N+21%C2%B000'43.9%22E/@52.2297,21.0122,17z/data=!3m1!4b1!4m4!3m3!8m2!3d52.229722!4d21.012194"
            withClue("a dropped pin has no name") { Places.target(dropped) shouldBe MapTarget(null, "52.229722" to "21.012194", null) }
            Places.target("https://maps.google.com/?q=52.23,21.01") shouldBe MapTarget(null, "52.23" to "21.01", null)
            Places.target("https://www.google.com/maps/search/?api=1&query=Hala+Stulecia+Wroc%C5%82aw") shouldBe
                MapTarget(null, null, "Hala Stulecia Wrocław")
            withClue("the searched text; ll is only the map's centre") {
                Places.target("https://www.google.pl/maps?q=O%C5%82%C3%B3wek&ll=51.1,17.0") shouldBe MapTarget(null, null, "Ołówek")
            }
            withClue("a search, not a place at the map's centre") {
                Places.target("https://www.google.com/maps/search/pizza/@52.2,21.0,14z") shouldBe MapTarget(null, null, "pizza")
            }
            withClue("directions: the destination") {
                Places.target(
                    "https://www.google.com/maps/dir/Home/Hala+Stulecia,+Wroc%C5%82aw/@51.1,17.0,12z/data=!4m8!1d17.07!2d51.10",
                ) shouldBe
                    MapTarget(null, null, "Hala Stulecia, Wrocław")
            }
            Places.target("https://www.google.com/maps/place/100%25+Club+%F0%9F%8E%B8") shouldBe MapTarget("100% Club 🎸", null, null)
            withClue("a malformed escape kept as it is") {
                Places.target("https://www.google.com/maps/place/A%-1B") shouldBe MapTarget("A%-1B", null, null)
            }
            withClue("a short link is followed first") { Places.target("https://maps.app.goo.gl/abc").shouldBeNull() }
            Places.target("https://example.com/maps/place/X/@1.0,2.0").shouldBeNull()
        }

        expect("a picked place is its name and town with its exact pin") {
            val stodola = PlaceSuggestion("ChIJ abc", "Stodoła", "Stefana Batorego 10, 02-591 Warszawa, Polska")
            withClue("the postal code is dropped") { Places.label(stodola) shouldBe "Stodoła, Warszawa" }
            withClue("a town alone") { Places.label(PlaceSuggestion("p", "Warszawa", "Polska")) shouldBe "Warszawa" }
            Places.label(PlaceSuggestion("p", "Hala Stulecia", null)) shouldBe "Hala Stulecia"
            withClue("the town already in the name") {
                Places.label(PlaceSuggestion("p", "Klub Wrocław", "Wrocław, Polska")) shouldBe "Klub Wrocław"
            }
            withClue("not the state") {
                Places.label(PlaceSuggestion("p", "Ryman Auditorium", "Broadway, Nashville, TN, USA")) shouldBe
                    "Ryman Auditorium, Nashville"
            }
            Places.label(PlaceSuggestion("p", "The Venue", "1 High St, London SW1A 1AA, UK")) shouldBe "The Venue, London"
        }
    })
