package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.Lineup
import com.jacekgajek.kapelusz.domain.Names
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.domain.sortedByName
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.ChangeLineup
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnknownOperation
import com.jacekgajek.kapelusz.ops.isUndoable
import com.jacekgajek.kapelusz.api.HistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ITERATION_8: the line-up picker's operation, alphabetical order, operations this version doesn't know. */
class LineupTest {

    private fun band() = Scenario("Alice", "Bob", "Carol", "Dave").event("gig", "Alice", "Bob", guests = listOf("Helper" to 100))

    private fun parties(scenario: Scenario) = scenario.band.event("gig")!!.participants.map { it.partyId }.toSet()

    @Test
    fun addsAndRemovesInOneStep() {
        val scenario = band()
        scenario.apply(ChangeLineup("gig", add = listOf(Participant("carol", 150), Participant("dave")), remove = listOf("bob", "helper")))
        assertEquals(setOf("alice", "carol", "dave"), parties(scenario))
        assertEquals(150, scenario.band.event("gig")!!.participant("carol")!!.weight)
        assertTrue(scenario.band.event("gig")!!.guests.isEmpty(), "a removed guest is gone, as with RemoveParticipant")
    }

    @Test
    fun newGuestsComeWithTheirShares() {
        val scenario = band()
        scenario.apply(ChangeLineup("gig", add = listOf(Participant("roadie", 50)), guests = listOf(Guest("roadie", "Roadie"))))
        assertEquals("Roadie", scenario.band.event("gig")!!.guest("roadie")!!.name)
        assertEquals(50, scenario.band.event("gig")!!.participant("roadie")!!.weight)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")), guests = listOf(Guest("x", "X")))).reason)
        assertEquals(RejectionReason.DUPLICATE_ID, scenario.rejected(ChangeLineup("gig", add = listOf(Participant("dave")), guests = listOf(Guest("dave", "Dave")))).reason)
    }

    @Test
    fun oneRefusalRefusesAll() {
        val scenario = band().apply(AddHolding("gig", "h", "bob", pln("100"), DOOR))
        val before = scenario.band
        val rejection = scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")), remove = listOf("alice", "bob")))
        assertEquals(RejectionReason.PARTICIPANT_HAS_MONEY, rejection.reason)
        assertEquals(before, scenario.band)
    }

    @Test
    fun theSingleOperationsRulesApply() {
        val scenario = band()
        assertEquals(RejectionReason.ALREADY_PARTICIPANT, scenario.rejected(ChangeLineup("gig", add = listOf(Participant("alice")))).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(ChangeLineup("gig")).reason, "nothing to change")
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")), remove = listOf("carol"))).reason)
        scenario.apply(SetMemberArchived("dave", true))
        assertEquals(RejectionReason.ARCHIVED_MEMBER, scenario.rejected(ChangeLineup("gig", add = listOf(Participant("dave")))).reason)
        scenario.userFor("carol", Role.VIEWER)
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(ChangeLineup("gig", add = listOf(Participant("carol")))).reason)
    }

    @Test
    fun leadersOnlyAndUndoable() {
        val scenario = band()
        val member = scenario.userFor("bob", Role.MEMBER)
        val op = ChangeLineup("gig", add = listOf(Participant("carol")))
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(op, member).reason)
        val lead = scenario.userFor("bob", Role.LEAD).also { scenario.apply(SetMemberRole("bob", Role.LEAD)) }
        val before = scenario.band
        scenario.apply(op, lead)
        assertTrue(isUndoable(op))
        scenario.apply(Undo("x", op, BandChange.between(before, scenario.band)), lead)
        assertEquals(before.event("gig")!!.participants, scenario.band.event("gig")!!.participants)
    }

    @Test
    fun peopleAreInAlphabeticalOrder() {
        val names = listOf("Żaneta", "Łukasz", "lena", "Zenon", "Ewa", "Źdźbło", "Lz", "Ćma", "Cyryl", "Müller", "Mark")
        assertEquals(listOf("Cyryl", "Ćma", "Ewa", "lena", "Lz", "Łukasz", "Mark", "Müller", "Zenon", "Źdźbło", "Żaneta"), names.sortedWith(Names.order))
        assertTrue(Names.matches("Łukasz Żółć", "lukasz zolc"))
        assertTrue(Names.matches("José", "JOSE"))
        assertFalse(Names.matches("Ewa", "ewka"))
    }

    @Test
    fun settlementLinesAreAlphabeticalMembersThenGuests() {
        val scenario = Scenario("Zoe", "Adam").apply(AddMember("lukasz", "Łukasz"))
            .event("gig", "Zoe", "Adam", "Lukasz", guests = listOf("Zed" to 100, "Bea" to 100))
        assertEquals(listOf("adam", "lukasz", "zoe", "bea", "zed"), SettlementCalculator.partyOrder(scenario.band, scenario.band.event("gig")!!))
        assertEquals(listOf("Adam", "Łukasz", "Zoe"), scenario.band.members.sortedByName { it.name }.map { it.name })
    }

    @Test
    fun anUnknownOperationIsReadAndNeverApplied() {
        val json = """{"opId":"o","clientId":"c","seq":1,"timestamp":2,"op":{"type":"MoveMember","memberId":"bob","toIndex":0}}"""
        val envelope = KapeluszJson.decodeFromString(OpEnvelope.serializer(), json)
        assertEquals(UnknownOperation, envelope.op)
        val history = """{"userId":"u","memberId":"m","at":1,"ops":[$json]}"""
        assertEquals(UnknownOperation, KapeluszJson.decodeFromString(HistoryEntry.serializer(), history).ops.single().op)
        assertEquals(RejectionReason.INVALID_INPUT, band().rejected(UnknownOperation).reason)
        assertFalse(isUndoable(UnknownOperation))
    }

    @Test
    fun startFromReplacesTheLineupWithAnotherEvents() {
        // gig: Alice, Bob, guest Helper; Bob has money here. old: Carol (150), Dave, guests Helper and Roadie (50).
        val scenario = band()
            .apply(AddHolding("gig", "h", "bob", pln("100"), DOOR))
            .apply(com.jacekgajek.kapelusz.ops.CreateEvent("old", "old", DATE, guests = listOf(Guest("helper2", "helper"), Guest("roadie", "Roadie")),
                participants = listOf(Participant("carol", 150), Participant("dave"), Participant("helper2"), Participant("roadie", 50))))
        val band = scenario.band
        val gig = band.event("gig")!!
        var next = 0
        val draft = Lineup.startFrom(band, gig, band.event("old")!!) { "g${next++}" }
        assertEquals(setOf("carol", "dave", "bob", "helper", "g0"), draft.ticked, "Alice out; Bob locked; Helper kept, not doubled")
        assertEquals(listOf(Guest("g0", "Roadie")), draft.newGuests)

        val op = Lineup.change(band, gig, draft)!!
        assertEquals(listOf("alice"), op.remove)
        assertEquals(mapOf("carol" to 150, "dave" to 100, "g0" to 50), op.add.associate { it.partyId to it.weight })
        scenario.apply(op)
        assertEquals(setOf("bob", "carol", "dave", "helper", "g0"), parties(scenario))
        assertEquals("Roadie", scenario.band.event("gig")!!.guest("g0")!!.name)
    }

    @Test
    fun saveSendsOnlyWhatChanged() {
        val scenario = band()
        val gig = scenario.band.event("gig")!!
        assertEquals(null, Lineup.change(scenario.band, gig, Lineup.Draft.of(gig)))
        val op = Lineup.change(scenario.band, gig, Lineup.Draft(setOf("alice", "carol")))!!
        assertEquals(listOf("bob", "helper"), op.remove)
        assertEquals(listOf(Participant("carol")), op.add)
        assertEquals(listOf("alice", "bob", "carol", "dave"), Lineup.candidates(scenario.band).map { it.id })
    }
}
