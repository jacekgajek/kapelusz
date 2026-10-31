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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class EventSearchTest {

    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
    }

    private val band = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
        CreateEvent("wedding", "Wesele Ani", LocalDate(2026, 9, 12), "Dwór, Krakowska 1, 31-000 Kraków"),
        CreateEvent("club", "Koncert", LocalDate(2026, 6, 1), "Klub Stodoła, Stefana Batorego 10, 02-591 Warszawa"),
        CreateEvent("lodz", "Juwenalia", LocalDate(2025, 5, 20), "Łódź"),
        CreateEvent("noplace", "Próba", LocalDate(2026, 1, 3)),
        SetEventArchived("lodz", true),
    )
    private val search = EventSearch(eventSummaries(BandView(band), band.member("me"), showArchived = true, onlyMyEvents = false))

    private fun find(query: String) = search.find(searchWords(query)).map { it.event.id }

    @Test
    fun foldsCaseAndDiacritics() {
        assertEquals("zazolc gesla jazn lodz", Names.fold("ZAŻÓŁĆ gęślą jaźń Łódź"))
        assertEquals("creme bruhe strasse", Names.fold("Crème Brühe Straße"))
        assertEquals("lodz", Names.fold("L\u006F\u0301dz"), "combining marks, as pasted from a Mac")
    }

    @Test
    fun blankQueryHasNoWords() {
        assertEquals(emptyList(), searchWords("   "))
        assertEquals(listOf("wesele", "krakow"), searchWords("  Wesele \tKraków "))
        assertEquals(listOf("wesele", "ani"), searchWords("wesele\u00A0ani"), "a non-breaking space separates words")
    }

    @Test
    fun matchesNameAndLocationWithoutPolishLetters() {
        assertEquals(listOf("lodz"), find("lodz"))
        assertEquals(listOf("club"), find("stodola"))
        assertEquals(listOf("noplace"), find("PROBA"))
    }

    @Test
    fun everyWordSomewhereInAnyOrder() {
        assertEquals(listOf("wedding"), find("krakow wesele"))
        assertEquals(listOf("wedding"), find("ani dwór"))
        assertEquals(emptyList(), find("wesele warszawa"))
    }

    @Test
    fun aWordDoesNotSpanNameAndLocation() {
        assertEquals(emptyList(), find("anidwór"))
        assertEquals(emptyList(), find("ani dwór kraków warszawa"))
    }

    @Test
    fun searchesEveryEventButArtistsOnlyTheirs() = runTest {
        val withBob = band.apply(
            AddMember("bob", "Bob"),
            CreateEvent("bobs", "Bob's gig", LocalDate(2026, 3, 1), participants = listOf(Participant("bob"))),
        )
        assertEquals(
            listOf("wedding", "club", "bobs", "noplace", "lodz"),
            searchableSummaries(BandView(withBob), withBob.member("me"), artist = false).map { it.event.id },
            "archived and other people's events too",
        )
        assertEquals(listOf("bobs"), searchableSummaries(BandView(withBob), withBob.member("bob"), artist = true).map { it.event.id })
    }

    @Test
    fun keepsTheListOrder() {
        assertEquals(listOf("wedding", "club", "noplace", "lodz"), find("o"))
    }
}
