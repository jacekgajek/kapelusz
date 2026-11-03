package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
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
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.rules.Permissions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalTime

class AgendaTest :
    ExpectSpec({

        expect("the default template is arrival and concert start") {
            val scenario = Scenario("Alice")
            scenario.band.effectiveAgendaTemplate.map { it.kind } shouldBe
                listOf(AgendaKind.ARRIVAL, AgendaKind.SOUND_CHECK, AgendaKind.CONCERT_START)
            scenario.apply(SetAgendaTemplate(listOf(AgendaTemplateItem(AgendaKind.ARRIVAL), AgendaTemplateItem(label = " Sound check "))))
            scenario.band.effectiveAgendaTemplate shouldBe
                listOf(AgendaTemplateItem(AgendaKind.ARRIVAL), AgendaTemplateItem(label = "Sound check"))
            scenario.rejected(SetAgendaTemplate(listOf(AgendaTemplateItem(label = " ")))).reason shouldBe RejectionReason.EMPTY_NAME
        }

        expect("events keep their own times") {
            val agenda = listOf(AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0)), AgendaTime("s", AgendaKind.CONCERT_START))
            val scenario = Scenario("Alice", "Bob")
                .apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice")), agenda = agenda))
            scenario.band.event("e")!!.agenda shouldBe agenda

            val withDinner = agenda + AgendaTime("d", label = "Dinner", time = LocalTime(19, 0))
            scenario.apply(SetEventAgenda("e", withDinner))
            scenario.apply(SetAgendaTemplate(emptyList()))
            withClue("a template change does not touch existing events") { scenario.band.event("e")!!.agenda shouldBe withDinner }
            scenario.rejected(SetEventAgenda("e", withDinner + withDinner.last())).reason shouldBe RejectionReason.DUPLICATE_ID
        }

        expect("early morning times are the next day") {
            val end = AgendaTime("x", label = "End", time = LocalTime(1, 30))
            end.isNextDay shouldBe true
            end.minutesOfEventDay shouldBe 25 * 60 + 30
            AgendaTime("y", label = "Rehearsal", time = LocalTime(10, 0)).minutesOfEventDay shouldBe 10 * 60
        }

        expect("participants remember who added them") {
            val scenario = Scenario("Alice", "Bob")
            val alice = Actor.User(scenario.band.member("alice")!!.userId ?: "u-alice")
            scenario.apply(CreateEvent("e", "Gig", DATE, participants = listOf(Participant("alice"))), alice)
            scenario.apply(AddParticipant("e", "bob"), alice)
            scenario.band.event("e")!!.participants.map { it.addedBy } shouldBe listOf("alice", "alice")
        }

        expect("permissions") {
            val scenario = Scenario("Alice", "Bob").apply(CreateEvent("e", "Gig", DATE))
            val band = scenario.band
            val owner = Actor.User(band.members.first { it.userId != null }.userId!!)
            Permissions.isAllowed(band, owner, SetAgendaTemplate(emptyList())) shouldBe true
            Permissions.isAllowed(band, owner, SetEventAgenda("e", emptyList())) shouldBe true
            Permissions.isAllowed(band, Actor.User("stranger"), SetEventAgenda("e", emptyList())) shouldBe false
        }

        expect("labels and ids are bounded") {
            val scenario = Scenario("Alice").apply(CreateEvent("e", "Gig", DATE))
            val long = "x".repeat(AgendaTime.MAX_LABEL + 1)
            scenario.rejected(SetEventAgenda("e", listOf(AgendaTime("a", label = long)))).reason shouldBe RejectionReason.INVALID_INPUT
            withClue("also on a built-in entry") {
                scenario.rejected(SetEventAgenda("e", listOf(AgendaTime("a", AgendaKind.ARRIVAL, label = long)))).reason shouldBe
                    RejectionReason.INVALID_INPUT
            }
            scenario.rejected(SetEventAgenda("e", listOf(AgendaTime("i".repeat(65), AgendaKind.ARRIVAL)))).reason shouldBe
                RejectionReason.INVALID_INPUT
            val tooMany = (1..AgendaTime.MAX_TIMES + 1).map { AgendaTime("t$it", label = "T$it") }
            scenario.rejected(SetEventAgenda("e", tooMany)).reason shouldBe RejectionReason.INVALID_INPUT
        }

        expect("a kind from a newer version does not break reading") {
            val time = KapeluszJson.decodeFromString(
                AgendaTime.serializer(),
                """{"id":"a","kind":"LOAD_IN","label":"Load-in","time":"18:00"}""",
            )
            time shouldBe AgendaTime("a", kind = null, label = "Load-in", time = LocalTime(18, 0))
        }
    })
