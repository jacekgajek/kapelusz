package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.screens.event.InfoWords
import com.jacekgajek.kapelusz.app.ui.screens.event.eventInfoText
import com.jacekgajek.kapelusz.model.Event
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

private val INFO_WORDS = InfoWords("Data", "Miejsce", "Mapa", "Plan", "Skład", "kapelusz.jacekgajek.com")

/** The shared gig info: parts apart by a blank line, missing ones left out, the address last. */
class EventInfoTextTest :
    ExpectSpec({
        val words = INFO_WORDS

        expect("everything the event has") {
            val event = Event(
                "e",
                "Wynaturzenie",
                LocalDate(2026, 11, 7),
                location = "Klub Pod Jaszczurami",
                notes = "Setlista:\n1. Intro\n",
            )
            val text = eventInfoText(event, listOf("Przyjazd 18:00", "Koncert 20:00"), listOf("Ania", "Bartek", "Kasia"), words, "pl")
            text shouldBe
                """
                Wynaturzenie

                Data: 07.11.2026
                Miejsce: Klub Pod Jaszczurami
                Mapa: https://www.google.com/maps/dir/?api=1&destination=Klub%20Pod%20Jaszczurami

                Plan
                - Przyjazd 18:00
                - Koncert 20:00

                Setlista:
                1. Intro

                Skład: Ania, Bartek, Kasia

                kapelusz.jacekgajek.com
                """.trimIndent()
        }

        expect("only what there is") {
            val event = Event("e", "Próba", LocalDate(2026, 1, 2), location = " ", notes = "  ")
            eventInfoText(event, emptyList(), emptyList(), words, "pl") shouldBe "Próba\n\nData: 02.01.2026\n\nkapelusz.jacekgajek.com"
            withClue("English dates as elsewhere in the app") {
                eventInfoText(event, emptyList(), emptyList(), words, "en") shouldBe "Próba\n\nData: 02 Jan 2026\n\nkapelusz.jacekgajek.com"
            }
        }
    })
