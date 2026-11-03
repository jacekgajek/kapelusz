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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** Files on collected entries and on events. */
class FilesTest :
    ExpectSpec({

        val entry = AttachmentOwner.OfHolding("gig", "h")
        val gigFiles = AttachmentOwner.OfEvent("gig")

        fun pdf(id: String, name: String = "$id.pdf") = Attachment(id, AttachmentKind.PDF, name, 1_000)

        fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
            .apply(AddHolding("gig", "h", "bob", pln("2000"), CONTRACT))

        expect("entry files follow who may edit the entry") {
            val scenario = gig()
            val bob = scenario.userFor("bob", Role.MEMBER)
            val carol = scenario.userFor("carol", Role.MEMBER)
            val alice = scenario.userFor("alice", Role.OWNER)
            scenario.apply(AddAttachment(entry, pdf("contract")), bob)
            withClue("not her money") {
                scenario.rejected(AddAttachment(entry, pdf("x")), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            scenario.apply(AddAttachment(entry, pdf("confirmation")), alice)
            scenario.apply(RenameAttachment(entry, "contract", "  Umowa klub.pdf "), bob)
            scenario.band.event("gig")!!.holdings.single().attachments.map { it.fileName } shouldBe
                listOf("Umowa klub.pdf", "confirmation.pdf")
            scenario.rejected(RenameAttachment(entry, "contract", " "), bob).reason shouldBe RejectionReason.EMPTY_NAME
            scenario.apply(RemoveAttachment(entry, "confirmation"), bob)
            scenario.band.event("gig")!!.holdings.single().attachments.map { it.id } shouldBe listOf("contract")
        }

        expect("event files are for leads") {
            val scenario = gig()
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.rejected(AddAttachment(gigFiles, pdf("rider")), bob).reason shouldBe RejectionReason.ROLE_REQUIRED
            repeat(MAX_EVENT_ATTACHMENTS) { scenario.apply(AddAttachment(gigFiles, pdf("f$it"))) }
            scenario.rejected(AddAttachment(gigFiles, pdf("one-more"))).reason shouldBe RejectionReason.TOO_MANY_ATTACHMENTS
            withClue("ids are unique in the band") {
                scenario.rejected(AddAttachment(entry, pdf("f0"))).reason shouldBe RejectionReason.DUPLICATE_ID
            }
            repeat(MAX_ATTACHMENTS) { scenario.apply(AddAttachment(entry, pdf("e$it"))) }
            scenario.rejected(AddAttachment(entry, pdf("one-more"))).reason shouldBe RejectionReason.TOO_MANY_ATTACHMENTS
        }

        expect("long device names are shortened and archived events are read-only") {
            val scenario = gig()
            scenario.apply(AddAttachment(gigFiles, pdf("long", "x".repeat(500))))
            scenario.band.event("gig")!!.attachments.single().fileName!!.length shouldBe Attachment.MAX_FILE_NAME
            scenario.apply(SettleSuggestions("gig")).apply(SetEventArchived("gig", true))
            scenario.rejected(AddAttachment(gigFiles, pdf("late"))).reason shouldBe RejectionReason.ARCHIVED_EVENT
            scenario.rejected(RenameAttachment(entry, "x", "y")).reason shouldBe RejectionReason.ARCHIVED_EVENT
        }

        expect("removing an entry with files cannot be undone") {
            val scenario = gig().apply(AddAttachment(entry, pdf("contract")))
            val before = scenario.band
            val remove = RemoveHolding("gig", "h")
            scenario.apply(remove)
            withClue("its file is deleted") {
                scenario.rejected(Undo("u", remove, BandChange.between(before, scenario.band))).reason shouldBe RejectionReason.NOT_UNDOABLE
            }
        }
    })
