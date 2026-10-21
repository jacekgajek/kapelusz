package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.auth.Permissions
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTemplateItem
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.effectiveAgendaTemplate
import com.jacekgajek.artsplit.ops.AddParticipant
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.SetAgendaTemplate
import com.jacekgajek.artsplit.ops.SetEventAgenda
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgendaTest {
    @Test
    fun theDefaultTemplateIsArrivalAndConcertStart() {
        val scenario = Scenario("Alice")
        assertEquals(listOf(AgendaKind.ARRIVAL, AgendaKind.CONCERT_START), scenario.band.effectiveAgendaTemplate.map { it.kind })
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
}
