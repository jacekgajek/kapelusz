package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.PlayedPlaces
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.CreateEvent
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Where we played" (ITERATION_7 §2f). */
class PlayedPlacesTest {
    private val today = LocalDate(2026, 10, 28)

    private fun band() = Scenario("Alice", "Bob").apply {
        apply(CreateEvent("old", "Old", LocalDate(2025, 3, 1), location = "Piwnica Artystyczna \"Pod Sowami\", Kraszewskiego 26, 50-229 Wrocław", participants = listOf(Participant("alice"))))
        apply(CreateEvent("new", "New", LocalDate(2026, 3, 1), location = "Pod Sowami, Wrocław", participants = listOf(Participant("alice"), Participant("bob"))))
        apply(CreateEvent("pin", "Field", LocalDate(2026, 4, 1), location = "Łąka, 51.000000, 17.000000", participants = listOf(Participant("alice"))))
        apply(CreateEvent("lost", "Lost", LocalDate(2026, 5, 1), location = "Atlantis", participants = listOf(Participant("alice"))))
        apply(CreateEvent("soon", "Soon", LocalDate(2026, 12, 1), location = "Future Hall", participants = listOf(Participant("alice"))))
        apply(CreateEvent("none", "No place", LocalDate(2026, 6, 1), participants = listOf(Participant("alice"))))
    }

    @Test
    fun concertsPlayedWithALocationOnlyAndAnArtistOnlyTheirs() {
        val scenario = band()
        assertEquals(listOf("old", "new", "pin", "lost"), PlayedPlaces.played(scenario.band, scenario.band.member("alice"), null, null, today).map { it.id })
        assertEquals(listOf("new", "pin", "lost"), PlayedPlaces.played(scenario.band, null, LocalDate(2026, 1, 1), null, today).map { it.id }, "the period")
        scenario.userFor("bob", Role.ARTIST)
        assertEquals(listOf("new"), PlayedPlaces.played(scenario.band, scenario.band.member("bob"), null, null, today).map { it.id })
        assertEquals(
            listOf("Piwnica Artystyczna \"Pod Sowami\", Kraszewskiego 26, 50-229 Wrocław", "Pod Sowami, Wrocław", "Atlantis", "Future Hall"),
            PlayedPlaces.toLocate(scenario.band.events),
            "the pin isn't looked up",
        )
    }

    @Test
    fun oneMarkerPerPlaceTitledByItsNewestConcert() {
        val scenario = band()
        val events = PlayedPlaces.played(scenario.band, null, null, null, today)
        // Both spellings of the venue a few metres apart; Atlantis not found.
        val at = mapOf("pod sowami, wrocław" to (51.10781 to 17.03312), "piwnica artystyczna \"pod sowami\", kraszewskiego 26, 50-229 wrocław" to (51.10779 to 17.03308))
        val (places, missing) = PlayedPlaces.places(events) { at[PlayedPlaces.key(it)] }
        assertEquals(listOf("Pod Sowami" to listOf("new", "old"), "Łąka" to listOf("pin")), places.map { it.title to it.events.map { e -> e.id } })
        assertEquals("Pod Sowami, Wrocław", places.first().address)
        assertEquals(listOf("lost"), missing.map { it.id })
    }
}
