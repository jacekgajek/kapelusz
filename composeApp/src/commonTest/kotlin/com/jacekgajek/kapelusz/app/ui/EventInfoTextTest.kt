package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.screens.event.InfoWords
import com.jacekgajek.kapelusz.app.ui.screens.event.eventInfoText
import com.jacekgajek.kapelusz.model.Event
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared gig info (ITERATION_5 §3): parts apart by a blank line, missing ones left out, the address last. */
class EventInfoTextTest {
    private val words = InfoWords("Data", "Miejsce", "Mapa", "Plan", "Skład", "kapelusz.jacekgajek.com")

    @Test
    fun everythingTheEventHas() {
        val event = Event(
            "e", "Wynaturzenie", LocalDate(2026, 11, 7),
            location = "Klub Pod Jaszczurami", mapUrl = "https://maps.app.goo.gl/abc", notes = "Setlista:\n1. Intro\n",
        )
        val text = eventInfoText(event, listOf("Przyjazd 18:00", "Koncert 20:00"), listOf("Ania", "Bartek", "Kasia"), words, "pl")
        assertEquals(
            """
            Wynaturzenie

            Data: 07.11.2026
            Miejsce: Klub Pod Jaszczurami
            Mapa: https://maps.app.goo.gl/abc

            Plan
            - Przyjazd 18:00
            - Koncert 20:00

            Setlista:
            1. Intro

            Skład: Ania, Bartek, Kasia

            kapelusz.jacekgajek.com
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun onlyWhatThereIs() {
        val event = Event("e", "Próba", LocalDate(2026, 1, 2), location = " ", notes = "  ")
        assertEquals("Próba\n\nData: 02.01.2026\n\nkapelusz.jacekgajek.com", eventInfoText(event, emptyList(), emptyList(), words, "pl"))
        assertEquals("Próba\n\nData: 02 Jan 2026\n\nkapelusz.jacekgajek.com", eventInfoText(event, emptyList(), emptyList(), words, "en"), "English dates as elsewhere in the app")
    }
}
