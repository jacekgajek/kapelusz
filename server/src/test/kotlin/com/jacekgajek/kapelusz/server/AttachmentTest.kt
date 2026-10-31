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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class AttachmentTest : ApiHarness() {
    private fun upload(user: String, attachmentId: String = "a1") = call(
        "POST",
        "/bands/band1/attachments",
        user,
        KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest(attachmentId)),
    )

    private val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 120_000)

    /** Alice owns the band and has a cost with one photo; Bob is a linked viewer. */
    private fun setup() {
        createBand()
        pushed("alice", AddMember("m-bob", "Bob"), AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)), AddAttachment("c1", photo))
        // A viewer, linked the way an accepted invite would do it.
        link("m-bob", "bob", Role.VIEWER)
    }

    @Test
    fun uploadThenDownload() {
        setup()
        val key = "bands/band1/attachments/a1.jpg"
        val presigned = upload("alice").parse(AttachmentUploadResponse.serializer())
        assertTrue(presigned.putUrl.contains(key) && presigned.putUrl.contains("length=120000"), presigned.putUrl)
        assertEquals("image/jpeg", presigned.headers["content-type"])

        val notYet = call("GET", "/bands/band1/attachments/a1", "bob")
        assertEquals(404, notYet.status)
        assertTrue(notYet.body!!.contains(ApiErrors.ATTACHMENT_NOT_UPLOADED))

        store.files += key
        assertEquals("memory://get/$key", call("GET", "/bands/band1/attachments/a1", "bob").parse(AttachmentUrlResponse.serializer()).url)
        assertEquals(409, upload("alice").status, "an uploaded file is never replaced")
    }

    @Test
    fun onlyListedAttachmentsAndNoViewers() {
        setup()
        assertEquals(404, upload("alice", "unknown").status)
        assertEquals(403, upload("bob").status)
        assertEquals(404, upload("mallory").status, "not a member")
        assertEquals(404, call("GET", "/bands/band1/attachments/a1", "mallory").status)
    }

    @Test
    fun removingDeletesTheFile() {
        setup()
        store.files += "bands/band1/attachments/a1.jpg"
        pushed("alice", RemoveAttachment("c1", "a1"))
        assertFalse(store.exists("bands/band1/attachments/a1.jpg"))

        pushed("alice", AddAttachment("c1", Attachment("a2", AttachmentKind.PDF, "invoice.pdf", 2_000_000)))
        store.files += "bands/band1/attachments/a2.pdf"
        pushed("alice", DeleteCost("c1"))
        assertFalse(store.exists("bands/band1/attachments/a2.pdf"), "deleting the cost deletes its files")
    }

    @Test
    fun sizeIsRequiredAndLimited() {
        setup()
        val big = Attachment("big", AttachmentKind.IMAGE, "x.jpg", AttachmentKind.IMAGE.maxBytes + 1)
        val tooLarge = pushed("alice", AddAttachment("c1", big))
        assertEquals(RejectionReason.ATTACHMENT_TOO_LARGE, tooLarge.rejected.values.single().reason)
        val noSize = pushed("alice", AddAttachment("c1", Attachment("nosize", AttachmentKind.PDF, "x.pdf")))
        assertEquals(RejectionReason.INVALID_INPUT, noSize.rejected.values.single().reason)
    }

    /** Files of collected entries and of events go the same way, and leave with what they belong to. */
    @Test
    fun filesOfCollectedMoneyAndEvents() {
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
        assertTrue(upload("alice", "contract").parse(AttachmentUploadResponse.serializer()).putUrl.contains("attachments/contract.pdf"))
        store.files += "bands/band1/attachments/contract.pdf"
        store.files += "bands/band1/attachments/rider.pdf"
        assertEquals("memory://get/bands/band1/attachments/rider.pdf", call("GET", "/bands/band1/attachments/rider", "bob").parse(AttachmentUrlResponse.serializer()).url)

        pushed("alice", com.jacekgajek.kapelusz.ops.RemoveHolding("gig", "h"))
        assertFalse(store.exists("bands/band1/attachments/contract.pdf"), "removing the entry deletes its files")
        pushed("alice", com.jacekgajek.kapelusz.ops.DeleteEvent("gig"))
        assertFalse(store.exists("bands/band1/attachments/rider.pdf"), "deleting the event deletes its files")
    }
}
