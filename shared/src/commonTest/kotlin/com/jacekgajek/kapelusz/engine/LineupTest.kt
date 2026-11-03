package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.engine.isUndoable
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ChangeLineup
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnknownOperation
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Lineup
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.text.Names
import com.jacekgajek.kapelusz.text.sortedByName
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The line-up picker's operation, alphabetical order, operations this version doesn't know. */
class LineupTest :
    ExpectSpec({

        fun band() = Scenario("Alice", "Bob", "Carol", "Dave").event("gig", "Alice", "Bob", guests = listOf("Helper" to 100))

        fun parties(scenario: Scenario) = scenario.band.event("gig")!!.participants.map { it.partyId }.toSet()

        expect("adds and removes in one step") {
            val scenario = band()
            scenario.apply(
                ChangeLineup("gig", add = listOf(Participant("carol", 150), Participant("dave")), remove = listOf("bob", "helper")),
            )
            parties(scenario) shouldBe setOf("alice", "carol", "dave")
            scenario.band.event("gig")!!.participant("carol")!!.weight shouldBe 150
            withClue("a removed guest is gone, as with RemoveParticipant") { scenario.band.event("gig")!!.guests.shouldBeEmpty() }
        }

        expect("new guests come with their shares") {
            val scenario = band()
            scenario.apply(ChangeLineup("gig", add = listOf(Participant("roadie", 50)), guests = listOf(Guest("roadie", "Roadie"))))
            scenario.band.event("gig")!!.guest("roadie")!!.name shouldBe "Roadie"
            scenario.band.event("gig")!!.participant("roadie")!!.weight shouldBe 50
            scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")), guests = listOf(Guest("x", "X")))).reason shouldBe
                RejectionReason.INVALID_INPUT
            val dupe = ChangeLineup("gig", add = listOf(Participant("dave")), guests = listOf(Guest("dave", "Dave")))
            scenario.rejected(dupe).reason shouldBe RejectionReason.DUPLICATE_ID
        }

        expect("one refusal refuses all") {
            val scenario = band().apply(AddHolding("gig", "h", "bob", pln("100"), DOOR))
            val before = scenario.band
            val rejection = scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")), remove = listOf("alice", "bob")))
            rejection.reason shouldBe RejectionReason.PARTICIPANT_HAS_MONEY
            scenario.band shouldBe before
        }

        expect("the single operation's rules apply") {
            val scenario = band()
            scenario.rejected(ChangeLineup("gig", add = listOf(Participant("alice")))).reason shouldBe RejectionReason.ALREADY_PARTICIPANT
            withClue("nothing to change") { scenario.rejected(ChangeLineup("gig")).reason shouldBe RejectionReason.INVALID_INPUT }
            scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")), remove = listOf("carol"))).reason shouldBe
                RejectionReason.INVALID_INPUT
            scenario.apply(SetMemberArchived("dave", true))
            scenario.rejected(ChangeLineup("gig", add = listOf(Participant("dave")))).reason shouldBe RejectionReason.ARCHIVED_MEMBER
            scenario.userFor("carol", Role.VIEWER)
            scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")))).reason shouldBe
                RejectionReason.VIEWER_NOT_PARTICIPANT
        }

        expect("leaders only and undoable") {
            val scenario = band()
            val member = scenario.userFor("bob", Role.MEMBER)
            val op = ChangeLineup("gig", add = listOf(Participant("carol")))
            scenario.rejected(op, member).reason shouldBe RejectionReason.ROLE_REQUIRED
            val lead = scenario.userFor("bob", Role.LEAD).also { scenario.apply(SetMemberRole("bob", Role.LEAD)) }
            val before = scenario.band
            scenario.apply(op, lead)
            isUndoable(op) shouldBe true
            scenario.apply(Undo("x", op, BandChange.between(before, scenario.band)), lead)
            scenario.band.event("gig")!!.participants shouldBe before.event("gig")!!.participants
        }

        expect("people are in alphabetical order") {
            val names = listOf("Żaneta", "Łukasz", "lena", "Zenon", "Ewa", "Źdźbło", "Lz", "Ćma", "Cyryl", "Müller", "Mark")
            names.sortedWith(Names.order) shouldBe
                listOf("Cyryl", "Ćma", "Ewa", "lena", "Lz", "Łukasz", "Mark", "Müller", "Zenon", "Źdźbło", "Żaneta")
            Names.matches("Łukasz Żółć", "lukasz zolc") shouldBe true
            Names.matches("José", "JOSE") shouldBe true
            Names.matches("Ewa", "ewka") shouldBe false
            Names.matches("Dvořák", "dvorak") shouldBe true
            Names.matches("Núñez", "nunez") shouldBe true
            listOf("Dora", "Cyryl", "Čapek").sortedWith(Names.order) shouldBe listOf("Čapek", "Cyryl", "Dora")
        }

        expect("settlement lines are alphabetical members then guests") {
            val scenario = Scenario("Zoe", "Adam").apply(AddMember("lukasz", "Łukasz"))
                .event("gig", "Zoe", "Adam", "Lukasz", guests = listOf("Zed" to 100, "Bea" to 100))
            SettlementCalculator.partyOrder(scenario.band, scenario.band.event("gig")!!) shouldBe
                listOf("adam", "lukasz", "zoe", "bea", "zed")
            scenario.band.members.sortedByName { it.name }.map { it.name } shouldBe listOf("Adam", "Łukasz", "Zoe")
        }

        expect("an unknown operation is read and never applied") {
            val json = """{"opId":"o","clientId":"c","seq":1,"timestamp":2,"op":{"type":"MoveMember","memberId":"bob","toIndex":0}}"""
            val envelope = KapeluszJson.decodeFromString(OpEnvelope.serializer(), json)
            envelope.op shouldBe UnknownOperation()
            val history = """{"userId":"u","memberId":"m","at":1,"ops":[$json]}"""
            KapeluszJson.decodeFromString(HistoryEntry.serializer(), history).ops.single().op shouldBe UnknownOperation()
            val newer = KapeluszJson.decodeFromString(OpEnvelope.serializer(), json.replace("\"memberId\":\"bob\"", "\"eventId\":\"gig\""))
            withClue("an event's History still lists it") { newer.op shouldBe UnknownOperation("gig") }
            band().rejected(UnknownOperation()).reason shouldBe RejectionReason.INVALID_INPUT
            isUndoable(UnknownOperation()) shouldBe false
        }

        expect("start from replaces the lineup with another event's") {
            // gig: Alice, Bob, guest Helper; Bob has money here. old: Carol (150), Dave, guests Helper and Roadie (50).
            val scenario = band()
                .apply(AddHolding("gig", "h", "bob", pln("100"), DOOR))
                .apply(
                    com.jacekgajek.kapelusz.ops.CreateEvent(
                        "old",
                        "old",
                        DATE,
                        guests = listOf(Guest("helper2", "helper"), Guest("roadie", "Roadie")),
                        participants = listOf(
                            Participant("carol", 150),
                            Participant("dave"),
                            Participant("helper2"),
                            Participant("roadie", 50),
                        ),
                    ),
                )
            val band = scenario.band
            val gig = band.event("gig")!!
            var next = 0
            val draft = Lineup.startFrom(band, gig, band.event("old")!!) { "g${next++}" }
            withClue("Alice out; Bob locked; Helper kept, not doubled") {
                draft.ticked(gig) shouldBe setOf("carol", "dave", "bob", "helper", "g0")
            }
            draft.newGuests shouldBe listOf(Guest("g0", "Roadie"))

            val op = Lineup.change(band, gig, draft)!!
            op.remove shouldBe listOf("alice")
            op.add.associate { it.partyId to it.weight } shouldBe mapOf("carol" to 150, "dave" to 100, "g0" to 50)
            scenario.apply(op)
            parties(scenario) shouldBe setOf("bob", "carol", "dave", "helper", "g0")
            scenario.band.event("gig")!!.guest("g0")!!.name shouldBe "Roadie"
        }

        expect("save sends only what changed") {
            val scenario = band()
            val gig = scenario.band.event("gig")!!
            Lineup.change(scenario.band, gig, Lineup.Draft()).shouldBeNull()
            val op = Lineup.change(scenario.band, gig, Lineup.Draft().ticking(gig, setOf("alice", "carol")))!!
            op.remove shouldBe listOf("bob", "helper")
            op.add shouldBe listOf(Participant("carol"))
            Lineup.candidates(scenario.band).map { it.id } shouldBe listOf("alice", "bob", "carol", "dave")
        }

        expect("a change synced meanwhile stays") {
            val scenario = band()
            val opened = scenario.band.event("gig")!!
            val draft = Lineup.Draft().toggled(opened, "carol")
            // Another leader adds Dave and removes Bob while the picker is open.
            scenario.apply(ChangeLineup("gig", add = listOf(Participant("dave")), remove = listOf("bob")))
            val now = scenario.band.event("gig")!!
            draft.ticked(now) shouldBe setOf("alice", "helper", "dave", "carol")
            val op = Lineup.change(scenario.band, now, draft)!!
            op.remove.shouldBeEmpty()
            op.add.map { it.partyId } shouldBe listOf("carol")
        }

        expect("start from keeps who can't be added back") {
            val scenario = band().apply(SetMemberArchived("bob", true))
            val gig = scenario.band.event("gig")!!
            scenario.apply(com.jacekgajek.kapelusz.ops.CreateEvent("old", "old", DATE, participants = listOf(Participant("carol"))))
            val draft = Lineup.startFrom(scenario.band, gig, scenario.band.event("old")!!) { "g" }
            withClue("Bob, a former member, stays; Alice and the guest go") { draft.ticked(gig) shouldBe setOf("bob", "carol") }
        }
    })
