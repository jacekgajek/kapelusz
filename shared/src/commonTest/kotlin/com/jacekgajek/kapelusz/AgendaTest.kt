package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.auth.Permissions
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalTime

class AgendaTest {
    @Test
    fun theDefaultTemplateIsArrivalAndConcertStart() {
        val scenario = Scenario("Alice")
        assertEquals(listOf(AgendaKind.ARRIVAL, AgendaKind.SOUND_CHECK, AgendaKind.CONCERT_START), scenario.band.effectiveAgendaTemplate.map { it.kind })
        scenario.apply(SetAgendaTemplate(listOf(AgendaTemplateItem(AgendaKind.ARRIVAL), AgendaTemplateItem(label = " Sound check "))))
        assertEquals(listOf(AgendaTemplateItem(AgendaKind.ARRIVAL), AgendaTemplateItem(label = "Sound check")), scenario.band.effectiveAgendaTemplate)
        assertEquals(RejectionReason.EMPTY_NAME, scenario.rejected(SetAgendaTemplate(listOf(AgendaTemplateItem(label = " ")))).reason)
    }

    @Test
    fun eventsKeepTheirOwnTimes() {
        val agenda = listOf(AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0)), AgendaTime("s", AgendaKind.CONCERT_START))
        val scenario = Scenario("Alice", "Bob")
            .apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice")), agenda = agenda))
        assertEquals(agenda, scenario.band.event("e")!!.agenda)

        val withDinner = agenda + AgendaTime("d", label = "Dinner", time = LocalTime(19, 0))
        scenario.apply(SetEventAgenda("e", withDinner))
        scenario.apply(SetAgendaTemplate(emptyList()))
        assertEquals(withDinner, scenario.band.event("e")!!.agenda, "a template change does not touch existing events")
        assertEquals(RejectionReason.DUPLICATE_ID, scenario.rejected(SetEventAgenda("e", withDinner + withDinner.last())).reason)
    }

    @Test
    fun earlyMorningTimesAreTheNextDay() {
        val end = AgendaTime("x", label = "End", time = LocalTime(1, 30))
        assertTrue(end.isNextDay)
        assertEquals(25 * 60 + 30, end.minutesOfEventDay)
        assertEquals(10 * 60, AgendaTime("y", label = "Rehearsal", time = LocalTime(10, 0)).minutesOfEventDay)
    }

    @Test
    fun participantsRememberWhoAddedThem() {
        val scenario = Scenario("Alice", "Bob")
        val alice = Actor.User(scenario.band.member("alice")!!.userId ?: "u-alice")
        scenario.apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice"))), alice)
        scenario.apply(AddParticipant("e", "bob"), alice)
        assertEquals(listOf("alice", "alice"), scenario.band.event("e")!!.participants.map { it.addedBy })
    }

    @Test
    fun permissions() {
        val scenario = Scenario("Alice", "Bob").apply(CreateEvent("e", "Gig", DATE))
        val band = scenario.band
        val owner = Actor.User(band.members.first { it.userId != null }.userId!!)
        assertTrue(Permissions.isAllowed(band, owner, SetAgendaTemplate(emptyList())))
        assertTrue(Permissions.isAllowed(band, owner, SetEventAgenda("e", emptyList())))
        assertFalse(Permissions.isAllowed(band, Actor.User("stranger"), SetEventAgenda("e", emptyList())))
    }

    @Test
    fun labelsAndIdsAreBounded() {
        val scenario = Scenario("Alice").apply(CreateEvent("e", "Gig", DATE))
        val long = "x".repeat(AgendaTime.MAX_LABEL + 1)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(SetEventAgenda("e", listOf(AgendaTime("a", label = long)))).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(SetEventAgenda("e", listOf(AgendaTime("a", AgendaKind.ARRIVAL, label = long)))).reason, "also on a built-in entry")
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(SetEventAgenda("e", listOf(AgendaTime("i".repeat(65), AgendaKind.ARRIVAL)))).reason)
        val tooMany = (1..AgendaTime.MAX_TIMES + 1).map { AgendaTime("t$it", label = "T$it") }
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(SetEventAgenda("e", tooMany)).reason)
    }

    @Test
    fun aKindFromANewerVersionDoesNotBreakReading() {
        val time = KapeluszJson.decodeFromString(AgendaTime.serializer(), """{"id":"a","kind":"LOAD_IN","label":"Load-in","time":"18:00"}""")
        assertEquals(AgendaTime("a", kind = null, label = "Load-in", time = LocalTime(18, 0)), time)
    }
}
