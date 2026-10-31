package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.IncomeSource.CONTRACT
import com.jacekgajek.kapelusz.model.MAX_ATTACHMENTS
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.RenameAttachment
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.pln
import kotlin.test.Test
import kotlin.test.assertEquals

/** ITERATION_4 §2.1: files on collected entries and on events. */
class FilesTest {
    private val entry = AttachmentOwner.OfHolding("gig", "h")
    private val gigFiles = AttachmentOwner.OfEvent("gig")

    private fun pdf(id: String, name: String = "$id.pdf") = Attachment(id, AttachmentKind.PDF, name, 1_000)

    private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
        .apply(AddHolding("gig", "h", "bob", pln("2000"), CONTRACT))

    @Test
    fun entryFilesFollowWhoMayEditTheEntry() {
        val scenario = gig()
        val bob = scenario.userFor("bob", Role.MEMBER)
        val carol = scenario.userFor("carol", Role.MEMBER)
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.apply(AddAttachment(entry, pdf("contract")), bob)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AddAttachment(entry, pdf("x")), carol).reason, "not her money")
        scenario.apply(AddAttachment(entry, pdf("confirmation")), alice)
        scenario.apply(RenameAttachment(entry, "contract", "  Umowa klub.pdf "), bob)
        assertEquals(listOf("Umowa klub.pdf", "confirmation.pdf"), scenario.band.event("gig")!!.holdings.single().attachments.map { it.fileName })
        assertEquals(RejectionReason.EMPTY_NAME, scenario.rejected(RenameAttachment(entry, "contract", " "), bob).reason)
        scenario.apply(RemoveAttachment(entry, "confirmation"), bob)
        assertEquals(listOf("contract"), scenario.band.event("gig")!!.holdings.single().attachments.map { it.id })
    }

    @Test
    fun eventFilesAreForLeads() {
        val scenario = gig()
        val bob = scenario.userFor("bob", Role.MEMBER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AddAttachment(gigFiles, pdf("rider")), bob).reason)
        repeat(MAX_EVENT_ATTACHMENTS) { scenario.apply(AddAttachment(gigFiles, pdf("f$it"))) }
        assertEquals(RejectionReason.TOO_MANY_ATTACHMENTS, scenario.rejected(AddAttachment(gigFiles, pdf("one-more"))).reason)
        assertEquals(RejectionReason.DUPLICATE_ID, scenario.rejected(AddAttachment(entry, pdf("f0"))).reason, "ids are unique in the band")
        repeat(MAX_ATTACHMENTS) { scenario.apply(AddAttachment(entry, pdf("e$it"))) }
        assertEquals(RejectionReason.TOO_MANY_ATTACHMENTS, scenario.rejected(AddAttachment(entry, pdf("one-more"))).reason)
    }

    @Test
    fun longDeviceNamesAreShortenedAndArchivedEventsAreReadOnly() {
        val scenario = gig()
        scenario.apply(AddAttachment(gigFiles, pdf("long", "x".repeat(500))))
        assertEquals(Attachment.MAX_FILE_NAME, scenario.band.event("gig")!!.attachments.single().fileName!!.length)
        scenario.apply(SettleSuggestions("gig")).apply(SetEventArchived("gig", true))
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(AddAttachment(gigFiles, pdf("late"))).reason)
        assertEquals(RejectionReason.ARCHIVED_EVENT, scenario.rejected(RenameAttachment(entry, "x", "y")).reason)
    }

    @Test
    fun removingAnEntryWithFilesCannotBeUndone() {
        val scenario = gig().apply(AddAttachment(entry, pdf("contract")))
        val before = scenario.band
        val remove = RemoveHolding("gig", "h")
        scenario.apply(remove)
        assertEquals(RejectionReason.NOT_UNDOABLE, scenario.rejected(Undo("u", remove, BandChange.between(before, scenario.band))).reason, "its file is deleted")
    }
}
