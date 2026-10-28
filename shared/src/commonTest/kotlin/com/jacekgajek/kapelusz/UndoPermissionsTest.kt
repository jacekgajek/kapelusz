package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.auth.Permissions
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.Undo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who may undo what, and who may add or remove attachments (ITERATION_3 R14). */
class UndoPermissionsTest {
    private val scenario = Scenario("Owner", "Lead", "Member", "Viewer")
        .event("gig", "Owner", "Lead", "Member")
        .apply(AddCost("c-member", "member", pln("50"), DATE))
        .apply(AddCost("c-owner", "owner", pln("50"), DATE))

    private val lead = scenario.userFor("lead", Role.LEAD)
    private val member = scenario.userFor("member", Role.MEMBER)
    private val viewer = scenario.userFor("viewer", Role.VIEWER)

    private val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 1000)

    private fun allowed(actor: Actor, op: Operation) = Permissions.isAllowed(scenario.band, actor, op)

    /** [op] applied by the system, and its undo, built from what it changed (as the server records it). */
    private fun recorded(op: Operation): Undo {
        val before = scenario.band
        scenario.apply(op)
        return Undo("undone", op, BandChange.between(before, scenario.band))
    }

    @Test
    fun attachmentsFollowTheirCost() {
        assertTrue(allowed(member, AddAttachment("c-member", photo)), "own cost")
        assertTrue(allowed(member, RemoveAttachment("c-member", "a1")))
        assertFalse(allowed(member, AddAttachment("c-owner", photo)), "someone else's cost")
        assertFalse(allowed(member, RemoveAttachment("c-owner", "a1")))
        assertTrue(allowed(lead, AddAttachment("c-owner", photo)), "leads manage every cost")
        assertTrue(allowed(lead, RemoveAttachment("c-owner", "a1")))
        assertFalse(allowed(viewer, AddAttachment("c-member", photo)))
        assertTrue(allowed(lead, AddAttachment("c-missing", photo)), "a missing cost needs a lead (and the reducer refuses it)")
        assertFalse(allowed(member, AddAttachment("c-missing", photo)))
    }

    @Test
    fun aLockedCostTakesMoreReceiptsButKeepsItsFiles() {
        // Refunded in an event with transfers: the owner of the cost can no longer change it nor remove its files, but
        // still adds a forgotten receipt (ITERATION_7 §2d).
        scenario.apply(SetRefund("gig", "c-member", pln("50")))
            .apply(AddHolding("gig", "h", "owner", pln("300"), DOOR))
            .apply(RecordTransfer("gig", "t", "owner", "member", pln("10")))
        assertTrue(allowed(member, AddAttachment("c-member", photo)))
        assertFalse(allowed(member, RemoveAttachment("c-member", "a1")))
        assertFalse(allowed(member, AddAttachment("c-owner", photo)), "not someone else's")
        // Undoing an added file removes it: refused like removing it.
        val undoAdd = recorded(AddAttachment("c-member", photo.copy(id = "a3")))
        assertFalse(allowed(member, undoAdd))
        assertTrue(allowed(lead, undoAdd))
        assertTrue(allowed(lead, AddAttachment("c-member", photo)))
    }

    @Test
    fun undoingAnAttachmentNeedsWhatAddingItNeeds() {
        val undoOwn = recorded(AddAttachment("c-member", photo))
        val undoOthers = recorded(AddAttachment("c-owner", photo.copy(id = "a2")))
        assertTrue(allowed(member, undoOwn))
        assertFalse(allowed(member, undoOthers))
        assertFalse(allowed(viewer, undoOwn))
        assertTrue(allowed(lead, undoOthers))
        scenario.apply(undoOwn, member)
        assertTrue(scenario.band.cost("c-member")!!.attachments.isEmpty())
    }

    @Test
    fun viewersUndoNothing() {
        val undoRename = recorded(RenameBand("New name"))
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(undoRename, viewer).reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(undoRename, member).reason, "renaming the band is for leaders")
        assertNull(Permissions.check(scenario.band, scenario.userFor("owner", Role.OWNER), undoRename))
    }

    @Test
    fun undoingAnUndoNeedsWhatBothDirectionsNeed() {
        // A member may record their own transfer, but removing one is for leads: so is undoing it, and redoing it.
        scenario.apply(AddHolding("gig", "h", "member", pln("100"), DOOR), member)
        val undoTransfer = recorded(RecordTransfer("gig", "t", "member", "owner", pln("10")))
        assertFalse(allowed(member, undoTransfer))
        val before = scenario.band
        scenario.apply(undoTransfer, lead)
        val redo = Undo("undo", undoTransfer, BandChange.between(before, scenario.band))
        assertFalse(allowed(member, redo), "a redo of a transfer's undo is no less than the undo")
        assertTrue(allowed(lead, redo))
        // An undo of an undo of the member's own cost stays theirs.
        val undoCost = recorded(AddCost("c2", "member", pln("5"), DATE))
        val beforeUndo = scenario.band
        scenario.apply(undoCost, member)
        assertTrue(allowed(member, Undo("undo2", undoCost, BandChange.between(beforeUndo, scenario.band))))
    }
}
