package com.jacekgajek.splitart

import com.jacekgajek.splitart.auth.Actor
import com.jacekgajek.splitart.model.Role
import com.jacekgajek.splitart.ops.AddCost
import com.jacekgajek.splitart.ops.AddParticipant
import com.jacekgajek.splitart.ops.BandChange
import com.jacekgajek.splitart.ops.BandPart
import com.jacekgajek.splitart.ops.Change
import com.jacekgajek.splitart.ops.DeleteEvent
import com.jacekgajek.splitart.ops.Engine
import com.jacekgajek.splitart.ops.LinkUser
import com.jacekgajek.splitart.ops.MoveMember
import com.jacekgajek.splitart.ops.OpEnvelope
import com.jacekgajek.splitart.ops.Operation
import com.jacekgajek.splitart.ops.RejectionReason
import com.jacekgajek.splitart.ops.RenameBand
import com.jacekgajek.splitart.ops.SetRefund
import com.jacekgajek.splitart.ops.TransferOwnership
import com.jacekgajek.splitart.ops.Undo
import com.jacekgajek.splitart.ops.UpdateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoTest {
    /** Applies [op] like the server does and returns its undo, built from what it changed. */
    private fun Scenario.recorded(op: Operation, actor: Actor = Actor.System): Undo {
        val before = band
        apply(op, actor)
        return Undo("undone", op, BandChange.between(before, band))
    }

    @Test
    fun undoPutsBackWhatTheChangeTouchedAndUndoingTheUndoRedoesIt() {
        val scenario = Scenario("Alice", "Bob").event("gig", "Alice")
        val undoRename = scenario.recorded(UpdateEvent("gig", name = Change("Big gig"), location = Change("Klub")))
        scenario.apply(RenameBand("Other name"))
        val redo = scenario.recorded(undoRename)
        assertEquals("gig", scenario.band.event("gig")!!.name)
        assertNull(scenario.band.event("gig")!!.location)
        assertEquals("Other name", scenario.band.name, "later, unrelated changes stay")
        scenario.apply(redo)
        assertEquals("Big gig", scenario.band.event("gig")!!.name, "undoing the undo redoes")
        assertEquals("Klub", scenario.band.event("gig")!!.location)
    }

    @Test
    fun refusedWhenWhatItTouchedChangedSince() {
        val scenario = Scenario("Alice").event("gig", "Alice")
        val undoRename = scenario.recorded(UpdateEvent("gig", name = Change("Big gig")))
        scenario.apply(UpdateEvent("gig", location = Change("Klub")))
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undoRename).reason)
    }

    @Test
    fun refusedWhenLaterChangesRelyOnIt() {
        val scenario = Scenario("Alice").event("gig", "Alice")
        val undoAddMember = scenario.recorded(com.jacekgajek.splitart.ops.AddMember("bob", "Bob"))
        scenario.apply(AddParticipant("gig", "bob"))
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undoAddMember).reason, "Bob takes part in the gig now")
    }

    @Test
    fun refusedWhenItWouldRefundACostTwice() {
        val scenario = Scenario("Alice").event("a", "Alice").event("b", "Alice")
            .apply(AddCost("c", "alice", pln("100"), DATE))
            .apply(SetRefund("a", "c", pln("100")))
        val undoDefer = scenario.recorded(SetRefund("a", "c", pln("0")))
        scenario.apply(SetRefund("b", "c", pln("100")))
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undoDefer).reason, "the cost is refunded at b now")
    }

    @Test
    fun aDeletedEventComesBackInItsPlace() {
        val scenario = Scenario("Alice").event("a", "Alice").event("b", "Alice").event("c", "Alice")
        val undoDelete = scenario.recorded(DeleteEvent("b"))
        scenario.apply(undoDelete)
        assertEquals(listOf("a", "b", "c"), scenario.band.events.map { it.id })
    }

    @Test
    fun aMoveIsUndoneInOrder() {
        val scenario = Scenario("Alice", "Bob", "Carol")
        val undoMove = scenario.recorded(MoveMember("carol", 0))
        scenario.apply(undoMove)
        assertEquals(listOf("alice", "bob", "carol"), scenario.band.members.map { it.id })
    }

    @Test
    fun membershipAndOwnershipCannotBeUndone() {
        val scenario = Scenario("Alice", "Bob")
        scenario.apply(LinkUser("bob", "user-bob", Role.ADMIN))
        val undoTransfer = scenario.recorded(TransferOwnership("bob"))
        assertEquals(RejectionReason.NOT_UNDOABLE, scenario.rejected(undoTransfer).reason)
        assertEquals(RejectionReason.NOT_UNDOABLE, scenario.rejected(Undo("x", RenameBand("x"), BandChange(BandPart(), BandPart()))).reason, "nothing recorded")
    }

    @Test
    fun onlyThoseWhoMayMakeTheChangeMayUndoIt() {
        val scenario = Scenario("Alice", "Bob").event("gig", "Alice")
        scenario.apply(LinkUser("bob", "user-bob", Role.MEMBER))
        val undoRename = scenario.recorded(UpdateEvent("gig", name = Change("Big gig")))
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(undoRename, Actor.User("user-bob")).reason)
        val undoBobsCost = scenario.recorded(AddCost("c", "bob", pln("10"), DATE), Actor.User("user-bob"))
        scenario.apply(undoBobsCost, Actor.User("user-bob"))
        assertNull(scenario.band.cost("c"))
    }

    @Test
    fun theEngineRecordsWhatEachOperationChanged() {
        val scenario = Scenario("Alice").event("gig", "Alice")
        val result = Engine.applyBatch(
            scenario.band, Actor.System,
            listOf(OpEnvelope("o1", "phone", 1, 1, UpdateEvent("gig", name = Change("X"))), OpEnvelope("o2", "phone", 2, 2, RenameBand("Y"))),
        )
        assertEquals(setOf("gig"), result.changes.getValue("o1").after.events.keys)
        assertEquals("Y", result.changes.getValue("o2").after.settings?.name)
        assertTrue(result.changes.getValue("o2").after.events.isEmpty())
    }

    @Test
    fun theFundStaysConsistent() {
        val scenario = Scenario("Alice").event("gig", "Alice").apply(com.jacekgajek.splitart.ops.SetTreasurer("alice"))
            .apply(com.jacekgajek.splitart.ops.SetFundAmount("gig", pln("100")))
        val undoZero = scenario.recorded(com.jacekgajek.splitart.ops.SetFundAmount("gig", pln("0")))
        scenario.apply(com.jacekgajek.splitart.ops.DisableBandFund)
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undoZero).reason, "the fund is off now")
    }

    @Test
    fun theTreasurerStaysActive() {
        val scenario = Scenario("Alice", "Bob", "Carol").apply(com.jacekgajek.splitart.ops.SetTreasurer("carol"))
        val undoBob = scenario.recorded(com.jacekgajek.splitart.ops.SetTreasurer("bob"))
        scenario.apply(com.jacekgajek.splitart.ops.SetMemberArchived("carol", true))
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undoBob).reason, "Carol is archived")
    }

    @Test
    fun onlyLeadsUndoTransfers() {
        val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
        scenario.apply(LinkUser("bob", "user-bob", Role.MEMBER))
        val undoTransfer = scenario.recorded(com.jacekgajek.splitart.ops.RecordTransfer("gig", "t", "bob", "alice", pln("10")))
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(undoTransfer, Actor.User("user-bob")).reason, "removing a transfer needs a lead")
    }

    @Test
    fun laterMovesSurviveAndAMoveIsNotUndoneOverAnother() {
        val scenario = Scenario("Alice", "Bob", "Carol", "Dave")
        val undoDelete = scenario.recorded(com.jacekgajek.splitart.ops.DeleteMember("carol"))
        scenario.apply(MoveMember("dave", 0))
        scenario.apply(undoDelete)
        assertEquals(listOf("dave", "alice", "bob", "carol"), scenario.band.members.map { it.id }, "Carol back after Bob; Dave's move kept")

        val undoMove = scenario.recorded(MoveMember("bob", 0))
        scenario.apply(MoveMember("carol", 1))
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undoMove).reason)
    }

    @Test
    fun photosAreNotChangesForUndo() {
        val scenario = Scenario("Alice", "Bob")
        scenario.apply(LinkUser("bob", "user-bob", Role.MEMBER))
        val undoRename = scenario.recorded(com.jacekgajek.splitart.ops.RenameMember("bob", "Robert"))
        // The server sets Bob's photo at his next sign-in (not an operation).
        val withPhoto = scenario.band.copy(members = scenario.band.members.map { if (it.id == "bob") it.copy(pictureUrl = "https://lh3.googleusercontent.com/b") else it })
        val undone = (Engine.apply(withPhoto, Actor.System, undoRename, "u", 9) as com.jacekgajek.splitart.ops.ApplyResult.Applied).band
        assertEquals("Bob", undone.member("bob")!!.name)
        assertEquals("https://lh3.googleusercontent.com/b", undone.member("bob")!!.pictureUrl, "the photo stays")
        val unlinked = (Engine.apply(undone, Actor.System, com.jacekgajek.splitart.ops.UnlinkUser("bob"), "x", 10) as com.jacekgajek.splitart.ops.ApplyResult.Applied).band
        assertNull(unlinked.member("bob")!!.pictureUrl, "the photo goes with the account")
    }
}
