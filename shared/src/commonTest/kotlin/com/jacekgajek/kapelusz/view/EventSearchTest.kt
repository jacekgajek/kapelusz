package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.text.Names
import com.jacekgajek.kapelusz.view.BandView
import com.jacekgajek.kapelusz.view.EventSearch
import com.jacekgajek.kapelusz.view.eventSummaries
import com.jacekgajek.kapelusz.view.searchWords
import com.jacekgajek.kapelusz.view.searchableSummaries
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
    (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
}

class EventSearchTest :
    ExpectSpec({
        coroutineTestScope = true

        val band = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
            CreateEvent("wedding", "Wesele Ani", LocalDate(2026, 9, 12), "Dwór, Krakowska 1, 31-000 Kraków"),
            CreateEvent("club", "Koncert", LocalDate(2026, 6, 1), "Klub Stodoła, Stefana Batorego 10, 02-591 Warszawa"),
            CreateEvent("lodz", "Juwenalia", LocalDate(2025, 5, 20), "Łódź"),
            CreateEvent("noplace", "Próba", LocalDate(2026, 1, 3)),
            SetEventArchived("lodz", true),
        )
        val search = EventSearch(eventSummaries(BandView(band), band.member("me"), showArchived = true, onlyMyEvents = false))

        fun find(query: String) = search.find(searchWords(query)).map { it.event.id }

        expect("folds case and diacritics") {
            Names.fold("ZAŻÓŁĆ gęślą jaźń Łódź") shouldBe "zazolc gesla jazn lodz"
            Names.fold("Crème Brühe Straße") shouldBe "creme bruhe strasse"
            withClue("combining marks, as pasted from a Mac") { Names.fold("L\u006F\u0301dz") shouldBe "lodz" }
        }

        expect("blank query has no words") {
            searchWords("   ") shouldBe emptyList()
            searchWords("  Wesele \tKraków ") shouldBe listOf("wesele", "krakow")
            withClue("a non-breaking space separates words") { searchWords("wesele\u00A0ani") shouldBe listOf("wesele", "ani") }
        }

        expect("matches name and location without Polish letters") {
            find("lodz") shouldBe listOf("lodz")
            find("stodola") shouldBe listOf("club")
            find("PROBA") shouldBe listOf("noplace")
        }

        expect("every word somewhere in any order") {
            find("krakow wesele") shouldBe listOf("wedding")
            find("ani dwór") shouldBe listOf("wedding")
            find("wesele warszawa") shouldBe emptyList()
        }

        expect("a word does not span name and location") {
            find("anidwór") shouldBe emptyList()
            find("ani dwór kraków warszawa") shouldBe emptyList()
        }

        expect("searches every event but artists only theirs") {
            val withBob = band.apply(
                AddMember("bob", "Bob"),
                CreateEvent("bobs", "Bob's gig", LocalDate(2026, 3, 1), participants = listOf(Participant("bob"))),
            )
            withClue("archived and other people's events too") {
                searchableSummaries(BandView(withBob), withBob.member("me"), artist = false).map { it.event.id } shouldBe
                    listOf("wedding", "club", "bobs", "noplace", "lodz")
            }
            searchableSummaries(BandView(withBob), withBob.member("bob"), artist = true).map { it.event.id } shouldBe listOf("bobs")
        }

        expect("keeps the list order") {
            find("o") shouldBe listOf("wedding", "club", "noplace", "lodz")
        }
    })
