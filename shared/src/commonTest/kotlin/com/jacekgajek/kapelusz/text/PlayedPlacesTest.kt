package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.text.PlayedPlaces
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

/** "Where we played". */
class PlayedPlacesTest :
    ExpectSpec({
        val today = LocalDate(2026, 10, 28)

        // Scenario is mutable, so each test builds its own.
        fun band() = Scenario("Alice", "Bob").apply {
            apply(
                CreateEvent(
                    "old",
                    "Old",
                    LocalDate(2025, 3, 1),
                    location = "Piwnica Artystyczna \"Pod Sowami\", Kraszewskiego 26, 50-229 Wrocław",
                    participants = listOf(Participant("alice")),
                ),
            )
            apply(
                CreateEvent(
                    "new",
                    "New",
                    LocalDate(2026, 3, 1),
                    location = "Pod Sowami, Wrocław",
                    participants = listOf(Participant("alice"), Participant("bob")),
                ),
            )
            apply(
                CreateEvent(
                    "pin",
                    "Field",
                    LocalDate(2026, 4, 1),
                    location = "Łąka, 51.000000, 17.000000",
                    participants = listOf(Participant("alice")),
                ),
            )
            apply(CreateEvent("lost", "Lost", LocalDate(2026, 5, 1), location = "Atlantis", participants = listOf(Participant("alice"))))
            apply(
                CreateEvent(
                    "soon",
                    "Soon",
                    LocalDate(2026, 12, 1),
                    location = "Future Hall",
                    participants = listOf(Participant("alice")),
                ),
            )
            apply(CreateEvent("none", "No place", LocalDate(2026, 6, 1), participants = listOf(Participant("alice"))))
        }

        expect("concerts played with a location only and an artist only theirs") {
            val scenario = band()
            withClue("no gigs to come") {
                PlayedPlaces.played(scenario.band, scenario.band.member("alice"), null, null, today).map { it.id } shouldBe
                    listOf("old", "new", "pin", "lost")
            }
            withClue("the period") {
                PlayedPlaces.played(scenario.band, null, LocalDate(2026, 1, 1), today, today).map { it.id } shouldBe
                    listOf("new", "pin", "lost")
            }
            scenario.userFor("bob", Role.ARTIST)
            PlayedPlaces.played(scenario.band, scenario.band.member("bob"), null, null, today).map { it.id } shouldBe listOf("new")
            withClue("the pin isn't looked up") {
                PlayedPlaces.toLocate(scenario.band.events) shouldBe
                    listOf("Piwnica Artystyczna \"Pod Sowami\", Kraszewskiego 26, 50-229 Wrocław", "Pod Sowami, Wrocław", "Atlantis", "Future Hall")
            }
        }

        expect("one marker per place titled by its newest concert") {
            val scenario = band()
            val events = PlayedPlaces.played(scenario.band, null, null, null, today)
            // Both spellings of the venue a few metres apart; Atlantis not found.
            val at = mapOf(
                "pod sowami, wrocław" to (51.10781 to 17.03312),
                "piwnica artystyczna \"pod sowami\", kraszewskiego 26, 50-229 wrocław" to (51.10779 to 17.03308),
            )
            val (places, missing) = PlayedPlaces.places(events) { at[PlayedPlaces.key(it)] }
            places.map { it.title to it.events.map { e -> e.id } } shouldBe
                listOf("Pod Sowami" to listOf("new", "old"), "Łąka" to listOf("pin"))
            places.first().address shouldBe "Pod Sowami, Wrocław"
            missing.map { it.id } shouldBe listOf("lost")
        }
    })
