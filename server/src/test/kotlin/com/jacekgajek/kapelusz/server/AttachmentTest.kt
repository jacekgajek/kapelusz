package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.AttachmentUrlResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

private class AttachmentApi : ApiHarness() {
    fun upload(user: String, attachmentId: String = "a1") = call(
        "POST",
        "/bands/band1/attachments",
        user,
        KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest(attachmentId)),
    )

    val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 120_000)

    /** Alice owns the band and has a cost with one photo; Bob is a linked viewer. */
    fun setup() {
        createBand()
        pushed("alice", AddMember("m-bob", "Bob"), AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)), AddAttachment("c1", photo))
        // A viewer, linked the way an accepted invite would do it.
        link("m-bob", "bob", Role.VIEWER)
    }
}

class AttachmentTest :
    ExpectSpec({

        expect("upload then download") {
            with(AttachmentApi()) {
                setup()
                val key = "bands/band1/attachments/a1.jpg"
                val presigned = upload("alice").parse(AttachmentUploadResponse.serializer())
                withClue(presigned.putUrl) {
                    presigned.putUrl shouldContain key
                    presigned.putUrl shouldContain "length=120000"
                }
                presigned.headers["content-type"] shouldBe "image/jpeg"

                val notYet = call("GET", "/bands/band1/attachments/a1", "bob")
                notYet.status shouldBe 404
                notYet.body!! shouldContain ApiErrors.ATTACHMENT_NOT_UPLOADED

                store.files += key
                call("GET", "/bands/band1/attachments/a1", "bob").parse(AttachmentUrlResponse.serializer()).url shouldBe "memory://get/$key"
                withClue("an uploaded file is never replaced") { upload("alice").status shouldBe 409 }
            }
        }

        expect("only listed attachments and no viewers") {
            with(AttachmentApi()) {
                setup()
                upload("alice", "unknown").status shouldBe 404
                upload("bob").status shouldBe 403
                withClue("not a member") { upload("mallory").status shouldBe 404 }
                call("GET", "/bands/band1/attachments/a1", "mallory").status shouldBe 404
            }
        }

        expect("removing deletes the file") {
            with(AttachmentApi()) {
                setup()
                store.files += "bands/band1/attachments/a1.jpg"
                pushed("alice", RemoveAttachment("c1", "a1"))
                store.exists("bands/band1/attachments/a1.jpg") shouldBe false

                pushed("alice", AddAttachment("c1", Attachment("a2", AttachmentKind.PDF, "invoice.pdf", 2_000_000)))
                store.files += "bands/band1/attachments/a2.pdf"
                pushed("alice", DeleteCost("c1"))
                withClue("deleting the cost deletes its files") { store.exists("bands/band1/attachments/a2.pdf") shouldBe false }
            }
        }

        expect("size is required and limited") {
            with(AttachmentApi()) {
                setup()
                val big = Attachment("big", AttachmentKind.IMAGE, "x.jpg", AttachmentKind.IMAGE.maxBytes + 1)
                val tooLarge = pushed("alice", AddAttachment("c1", big))
                tooLarge.rejected.values.single().reason shouldBe RejectionReason.ATTACHMENT_TOO_LARGE
                val noSize = pushed("alice", AddAttachment("c1", Attachment("nosize", AttachmentKind.PDF, "x.pdf")))
                noSize.rejected.values.single().reason shouldBe RejectionReason.INVALID_INPUT
            }
        }

        // Files of collected entries and of events go the same way, and leave with what they belong to.
        expect("files of collected money and events") {
            with(AttachmentApi()) {
                setup()
                val entry = com.jacekgajek.kapelusz.model.AttachmentOwner.OfHolding("gig", "h")
                val gig = com.jacekgajek.kapelusz.model.AttachmentOwner.OfEvent("gig")
                pushed(
                    "alice",
                    com.jacekgajek.kapelusz.ops.CreateEvent("gig", "Gig", LocalDate(2026, 10, 1), participants = listOf(com.jacekgajek.kapelusz.model.Participant("m-alice"))),
                    com.jacekgajek.kapelusz.ops.AddHolding("gig", "h", "m-alice", Money(200000), com.jacekgajek.kapelusz.model.IncomeSource.CONTRACT),
                    AddAttachment(entry, Attachment("contract", AttachmentKind.PDF, "contract.pdf", 300_000)),
                    AddAttachment(gig, Attachment("rider", AttachmentKind.PDF, "rider.pdf", 100_000)),
                )
                upload("alice", "contract").parse(AttachmentUploadResponse.serializer()).putUrl shouldContain "attachments/contract.pdf"
                store.files += "bands/band1/attachments/contract.pdf"
                store.files += "bands/band1/attachments/rider.pdf"
                call("GET", "/bands/band1/attachments/rider", "bob").parse(AttachmentUrlResponse.serializer()).url shouldBe
                    "memory://get/bands/band1/attachments/rider.pdf"

                pushed("alice", com.jacekgajek.kapelusz.ops.RemoveHolding("gig", "h"))
                withClue("removing the entry deletes its files") { store.exists("bands/band1/attachments/contract.pdf") shouldBe false }
                pushed("alice", com.jacekgajek.kapelusz.ops.DeleteEvent("gig"))
                withClue("deleting the event deletes its files") { store.exists("bands/band1/attachments/rider.pdf") shouldBe false }
            }
        }
    })
