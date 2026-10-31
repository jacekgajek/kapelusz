package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.auth.Permissions
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SettlementProblem
import com.jacekgajek.kapelusz.domain.SuggestedTransfer
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.isUndoable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shares as a percentage or a fixed amount (ITERATION_3 §2.4). */
class FixedShareTest {

    private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")

    @Test
    fun aGuestCanBeAddedWithAFixedAmount() {
        val scenario = gig()
            .apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
            .apply(AddGuest("gig", "dave", "Dave", fixedShare = pln("100")))
        assertEquals(pln("100"), scenario.band.event("gig")!!.participant("dave")!!.fixedShare)
        assertEquals(pln("100"), scenario.settle("gig").line("dave")!!.share)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(AddGuest("gig", "erin", "Erin", fixedShare = pln("0"))).reason)
    }

    @Test
    fun fixedAmountsComeOffTheTopAndTheRestIsSplitByWeight() {
        val scenario = gig()
            .apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
            .apply(SetFixedShare("gig", "carol", pln("100")))
        val s = scenario.settle("gig")
        assertEquals(pln("1000"), s.net)
        assertEquals(pln("100"), s.fixedTotal)
        assertEquals(pln("900"), s.rest)
        assertEquals(Money.ZERO, s.shortfall)
        assertEquals(mapOf("alice" to pln("450"), "bob" to pln("450"), "carol" to pln("100")), s.targets)
        assertTrue(s.line("carol")!!.isFixed)
        assertFalse(s.line("alice")!!.isFixed)
        assertTrue(s.problems.isEmpty())

        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun refundsArePaidOnTopOfTheFixedAmount() {
        val scenario = gig()
            .apply(AddCost("c", "carol", pln("30"), DATE, incurredForEventId = "gig"))
            .apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
            .apply(SetFixedShare("gig", "carol", pln("100")))
        val s = scenario.settle("gig")
        assertEquals(pln("970"), s.net)
        assertEquals(pln("100"), s.line("carol")!!.share)
        assertEquals(pln("30"), s.line("carol")!!.refunds)
        assertEquals(mapOf("alice" to pln("435"), "bob" to pln("435"), "carol" to pln("130")), s.targets)
    }

    @Test
    fun aShortfallIsALoss() {
        val scenario = gig()
            .apply(AddHolding("gig", "h", "alice", pln("150"), DOOR))
            .apply(SetFixedShare("gig", "bob", pln("100")))
            .apply(SetFixedShare("gig", "carol", pln("100")))
        val s = scenario.settle("gig")
        assertEquals(EventStatus.LOSS, s.status)
        assertEquals(setOf(SettlementProblem.LOSS), s.problems)
        // Income covers the net, but not the fixed amounts: the shortfall is what the fixed amounts exceed it by.
        assertEquals(pln("150"), s.net)
        assertEquals(pln("200"), s.fixedTotal)
        assertEquals(pln("-50"), s.rest)
        assertEquals(pln("50"), s.shortfall)
        assertTrue(s.suggestions.isEmpty())
        // The fixed amounts stay owed: the targets show what the income would have to cover.
        assertEquals(pln("100"), s.line("carol")!!.target)
        assertEquals(pln("0"), s.line("alice")!!.target)

        scenario.apply(SetFixedShare("gig", "carol", pln("50")))
        assertEquals(EventStatus.OPEN, scenario.settle("gig").status)
    }

    @Test
    fun anUnclaimedRestIsNoShares() {
        val scenario = Scenario("Alice", "Bob")
            .apply(CreateEvent("e", "e", DATE, participants = listOf(Participant("alice", 0), Participant("bob", fixedShare = pln("100")))))
            .apply(AddHolding("e", "h", "alice", pln("150"), DOOR))
        assertEquals(setOf(SettlementProblem.NO_SHARES), scenario.settle("e").problems)

        // Nothing left after the fixed amount: nobody needs a percentage.
        scenario.apply(SetFixedShare("e", "bob", pln("150")))
        val s = scenario.settle("e")
        assertTrue(s.problems.isEmpty())
        assertEquals(listOf(SuggestedTransfer("alice", "bob", pln("150"))), s.suggestions)
    }

    @Test
    fun theRestIsRoundedAmongTheOthersInPartyOrder() {
        val scenario = Scenario("Alice", "Bob", "Carol", "Dave").event("gig", "Alice", "Bob", "Carol", "Dave")
            .apply(AddHolding("gig", "h", "alice", pln("110.01"), DOOR))
            .apply(SetFixedShare("gig", "alice", pln("10")))
        val s = scenario.settle("gig")
        assertEquals(
            mapOf("alice" to pln("10"), "bob" to pln("33.34"), "carol" to pln("33.34"), "dave" to pln("33.33")),
            s.targets,
        )
        assertEquals(s.income, s.lines.fold(Money.ZERO) { sum, line -> sum + line.target })
    }

    @Test
    fun switchingBackRestoresTheWeight() {
        val scenario = gig().apply(SetWeight("gig", "bob", 150)).apply(SetFixedShare("gig", "bob", pln("80")))
        assertEquals(Participant("bob", 150, addedBy = null, fixedShare = pln("80")), scenario.band.event("gig")!!.participant("bob"))
        scenario.apply(SetFixedShare("gig", "bob", null))
        val bob = scenario.band.event("gig")!!.participant("bob")!!
        assertEquals(150, bob.weight)
        assertNull(bob.fixedShare)
    }

    @Test
    fun switchingBackWithANewWeightIsOneOperationAndOneUndo() {
        val scenario = gig().apply(SetFixedShare("gig", "bob", pln("80")))
        val before = scenario.band
        val op = SetFixedShare("gig", "bob", null, weight = 50)
        scenario.apply(op)
        assertEquals(Participant("bob", 50, addedBy = null), scenario.band.event("gig")!!.participant("bob"))
        // Without a weight the old one stays.
        assertEquals(100, gig().apply(SetFixedShare("gig", "bob", pln("80"))).apply(SetFixedShare("gig", "bob", null)).band.event("gig")!!.participant("bob")!!.weight)

        scenario.apply(Undo("undone", op, BandChange.between(before, scenario.band)))
        assertEquals(Participant("bob", 100, addedBy = null, fixedShare = pln("80")), scenario.band.event("gig")!!.participant("bob"))
    }

    @Test
    fun reducerValidatesTheParticipantAndTheAmount() {
        val scenario = gig()
        assertEquals(RejectionReason.NOT_FOUND, scenario.rejected(SetFixedShare("gig", "nobody", pln("10"))).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetFixedShare("gig", "bob", pln("0"))).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetFixedShare("gig", "bob", pln("-5"))).reason)
        // The weight that comes with switching back is checked as by SetWeight.
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetFixedShare("gig", "bob", null, weight = -1)).reason)
        assertEquals(RejectionReason.INVALID_SHARE, scenario.rejected(SetFixedShare("gig", "bob", null, weight = 10_001)).reason)
        assertEquals(
            RejectionReason.INVALID_AMOUNT,
            scenario.rejected(CreateEvent("e2", "e2", DATE, participants = listOf(Participant("alice", fixedShare = pln("0"))))).reason,
        )
        scenario.apply(SetEventArchived("gig", true))
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(SetFixedShare("gig", "bob", pln("10"))).reason)
    }

    @Test
    fun amountsAreBounded() {
        val scenario = gig()
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(SetFixedShare("gig", "bob", Money(Limits.MAX_AMOUNT + 1))).reason)
        assertEquals(
            RejectionReason.INVALID_AMOUNT,
            scenario.rejected(CreateEvent("e2", "e2", DATE, participants = listOf(Participant("alice", fixedShare = Money(Limits.MAX_AMOUNT + 1))))).reason,
        )
        // The largest fixed amounts for every participant still settle without overflow.
        listOf("alice", "bob", "carol").forEach { scenario.apply(SetFixedShare("gig", it, Money(Limits.MAX_AMOUNT))) }
        repeat(3) { scenario.apply(AddHolding("gig", "h$it", "alice", Money(Limits.MAX_AMOUNT), DOOR)) }
        assertTrue(scenario.settle("gig").problems.isEmpty())
    }

    @Test
    fun onlyLeadsSetFixedShares() {
        val scenario = gig()
        val lead = scenario.userFor("bob", Role.LEAD)
        val member = scenario.userFor("carol", Role.MEMBER)
        fun allowed(actor: Actor, op: Operation) = Permissions.isAllowed(scenario.band, actor, op)
        assertTrue(allowed(lead, SetFixedShare("gig", "carol", pln("10"))))
        assertTrue(allowed(lead, SetFixedShare("gig", "carol", null)))
        assertFalse(allowed(member, SetFixedShare("gig", "carol", pln("10"))), "not even for themselves")
    }

    @Test
    fun undoPutsThePercentageBackAndRedoTheAmount() {
        val scenario = gig()
        val op = SetFixedShare("gig", "carol", pln("100"))
        assertTrue(isUndoable(op))
        val before = scenario.band
        scenario.apply(op)
        val undo = Undo("undone", op, BandChange.between(before, scenario.band))
        val beforeUndo = scenario.band
        scenario.apply(undo)
        assertNull(scenario.band.event("gig")!!.participant("carol")!!.fixedShare)

        scenario.apply(Undo("redone", undo, BandChange.between(beforeUndo, scenario.band)))
        assertEquals(pln("100"), scenario.band.event("gig")!!.participant("carol")!!.fixedShare)
    }

    @Test
    fun serializesAndOldDataReadsAsPercentages() {
        listOf(SetFixedShare("gig", "carol", pln("100")), SetFixedShare("gig", "carol", null), SetFixedShare("gig", "carol", null, 50)).forEach { op ->
            val envelope = OpEnvelope("op1", "client", 1, 42, op)
            val json = KapeluszJson.encodeToString(OpEnvelope.serializer(), envelope)
            assertTrue("\"type\":\"SetFixedShare\"" in json, json)
            assertEquals(envelope, KapeluszJson.decodeFromString(OpEnvelope.serializer(), json))
        }
        val band = gig().apply(SetFixedShare("gig", "carol", pln("100"))).band
        val json = KapeluszJson.encodeToString(Band.serializer(), band)
        assertTrue("\"fixedShare\":10000" in json, json)
        assertEquals(band, KapeluszJson.decodeFromString(Band.serializer(), json))
        assertEquals(Participant("bob"), KapeluszJson.decodeFromString(Participant.serializer(), """{"partyId":"bob"}"""))
        // Operations from before the weight read without it.
        assertEquals(
            SetFixedShare("gig", "carol", null),
            KapeluszJson.decodeFromString(Operation.serializer(), """{"type":"SetFixedShare","eventId":"gig","partyId":"carol","amount":null}"""),
        )
    }
}
