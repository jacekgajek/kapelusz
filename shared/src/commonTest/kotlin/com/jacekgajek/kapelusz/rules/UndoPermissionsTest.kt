package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.rules.Permissions
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Who may undo what, and who may add or remove attachments. */
class UndoPermissionsTest :
    ExpectSpec({

        // The scenario is mutable and the tests change it: every test gets a fresh instance of the spec.
        isolationMode = IsolationMode.InstancePerRoot

        val scenario = Scenario("Owner", "Lead", "Member", "Viewer")
            .event("gig", "Owner", "Lead", "Member")
            .apply(AddCost("c-member", "member", pln("50"), DATE))
            .apply(AddCost("c-owner", "owner", pln("50"), DATE))

        val lead = scenario.userFor("lead", Role.LEAD)
        val member = scenario.userFor("member", Role.MEMBER)
        val viewer = scenario.userFor("viewer", Role.VIEWER)

        val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 1000)

        fun allowed(actor: Actor, op: Operation) = Permissions.isAllowed(scenario.band, actor, op)

        /** [op] applied by the system, and its undo, built from what it changed (as the server records it). */
        fun recorded(op: Operation): Undo {
            val before = scenario.band
            scenario.apply(op)
            return Undo("undone", op, BandChange.between(before, scenario.band))
        }

        expect("attachments follow their cost") {
            withClue("own cost") { allowed(member, AddAttachment("c-member", photo)) shouldBe true }
            allowed(member, RemoveAttachment("c-member", "a1")) shouldBe true
            withClue("someone else's cost") { allowed(member, AddAttachment("c-owner", photo)) shouldBe false }
            allowed(member, RemoveAttachment("c-owner", "a1")) shouldBe false
            withClue("leads manage every cost") { allowed(lead, AddAttachment("c-owner", photo)) shouldBe true }
            allowed(lead, RemoveAttachment("c-owner", "a1")) shouldBe true
            allowed(viewer, AddAttachment("c-member", photo)) shouldBe false
            withClue("a missing cost needs a lead (and the reducer refuses it)") {
                allowed(lead, AddAttachment("c-missing", photo)) shouldBe true
            }
            allowed(member, AddAttachment("c-missing", photo)) shouldBe false
        }

        expect("a locked cost takes more receipts but keeps its files") {
            // Refunded in an event with transfers: the owner of the cost can no longer change it nor remove its files, but
            // still adds a forgotten receipt.
            scenario.apply(SetRefund("gig", "c-member", pln("50")))
                .apply(AddHolding("gig", "h", "owner", pln("300"), DOOR))
                .apply(RecordTransfer("gig", "t", "owner", "member", pln("10")))
            allowed(member, AddAttachment("c-member", photo)) shouldBe true
            allowed(member, RemoveAttachment("c-member", "a1")) shouldBe false
            withClue("not someone else's") { allowed(member, AddAttachment("c-owner", photo)) shouldBe false }
            // Undoing an added file removes it: refused like removing it.
            val undoAdd = recorded(AddAttachment("c-member", photo.copy(id = "a3")))
            allowed(member, undoAdd) shouldBe false
            allowed(lead, undoAdd) shouldBe true
            allowed(lead, AddAttachment("c-member", photo)) shouldBe true
        }

        expect("undoing an attachment needs what adding it needs") {
            val undoOwn = recorded(AddAttachment("c-member", photo))
            val undoOthers = recorded(AddAttachment("c-owner", photo.copy(id = "a2")))
            allowed(member, undoOwn) shouldBe true
            allowed(member, undoOthers) shouldBe false
            allowed(viewer, undoOwn) shouldBe false
            allowed(lead, undoOthers) shouldBe true
            scenario.apply(undoOwn, member)
            scenario.band.cost("c-member")!!.attachments.shouldBeEmpty()
        }

        expect("viewers undo nothing") {
            val undoRename = recorded(RenameBand("New name"))
            scenario.rejected(undoRename, viewer).reason shouldBe RejectionReason.ROLE_REQUIRED
            withClue("renaming the band is for leaders") {
                scenario.rejected(undoRename, member).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            Permissions.check(scenario.band, scenario.userFor("owner", Role.OWNER), undoRename).shouldBeNull()
        }

        expect("undoing an undo needs what both directions need") {
            // A member may record their own transfer, but removing one is for leads: so is undoing it, and redoing it.
            scenario.apply(AddHolding("gig", "h", "member", pln("100"), DOOR), member)
            val undoTransfer = recorded(RecordTransfer("gig", "t", "member", "owner", pln("10")))
            allowed(member, undoTransfer) shouldBe false
            val before = scenario.band
            scenario.apply(undoTransfer, lead)
            val redo = Undo("undo", undoTransfer, BandChange.between(before, scenario.band))
            withClue("a redo of a transfer's undo is no less than the undo") { allowed(member, redo) shouldBe false }
            allowed(lead, redo) shouldBe true
            // An undo of an undo of the member's own cost stays theirs.
            val undoCost = recorded(AddCost("c2", "member", pln("5"), DATE))
            val beforeUndo = scenario.band
            scenario.apply(undoCost, member)
            allowed(member, Undo("undo2", undoCost, BandChange.between(beforeUndo, scenario.band))) shouldBe true
        }
    })
