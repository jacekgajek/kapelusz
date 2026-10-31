package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.MAX_ATTACHMENTS
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import com.jacekgajek.kapelusz.model.allAttachments

/** Files on costs, received-money entries and events. Pure state transitions and their validation; see [Reducer]. */
internal object AttachmentReducer {
    fun apply(band: Band, op: AttachmentOp): Band = when (op) {
        is AddAttachment -> band.updateAttachments(op.owner) { attachments ->
            val max = if (op.owner is AttachmentOwner.OfEvent) MAX_EVENT_ATTACHMENTS else MAX_ATTACHMENTS
            invalidIf(attachments.size >= max, RejectionReason.TOO_MANY_ATTACHMENTS) { "At most $max attachments here" }
            // Unique in the whole band: the id names its file in storage (ITERATION_3 R6).
            invalidIf(band.allAttachments().any { it.id == op.attachment.id }, RejectionReason.DUPLICATE_ID) { "Attachment ${op.attachment.id} already exists" }
            // The size is signed into the upload URL, so the stored file cannot be larger than declared here.
            val size = op.attachment.sizeBytes
            invalidIf(size == null || size <= 0, RejectionReason.INVALID_INPUT) { "Attachment size is required" }
            invalidIf(size > op.attachment.kind.maxBytes, RejectionReason.ATTACHMENT_TOO_LARGE) {
                "Attachment exceeds ${op.attachment.kind.maxBytes} bytes"
            }
            invalidIf(!op.attachment.id.matches(Regex("[A-Za-z0-9_-]{1,64}")), RejectionReason.INVALID_INPUT) { "Invalid attachment id" }
            // A device's file name can be anything: kept short rather than refused.
            attachments + op.attachment.copy(fileName = op.attachment.fileName?.trim()?.take(Attachment.MAX_FILE_NAME)?.ifEmpty { null })
        }

        is RemoveAttachment -> band.updateAttachments(op.owner) { attachments ->
            attachments.firstOrNull { it.id == op.attachmentId }.orNotFound { "Attachment ${op.attachmentId}" }
            attachments.filterNot { it.id == op.attachmentId }
        }

        is RenameAttachment -> band.updateAttachments(op.owner) { attachments ->
            attachments.firstOrNull { it.id == op.attachmentId }.orNotFound { "Attachment ${op.attachmentId}" }
            val name = validFileName(op.fileName)
            attachments.map { if (it.id == op.attachmentId) it.copy(fileName = name) else it }
        }
    }

    /** A file name people see and download under: not empty; a long one is shortened, as when a file is added. */
    private fun validFileName(name: String): String = name.trim().take(Attachment.MAX_FILE_NAME).also {
        invalidIf(it.isEmpty(), RejectionReason.EMPTY_NAME) { "File name cannot be empty" }
    }

    /** The attachments of a cost, a collected entry or an event (ITERATION_4 §2.1), changed by [transform]. */
    private inline fun Band.updateAttachments(owner: AttachmentOwner, transform: (List<Attachment>) -> List<Attachment>): Band = when (owner) {
        is AttachmentOwner.OfCost -> updateCost(owner.costId) { it.copy(attachments = transform(it.attachments)) }

        is AttachmentOwner.OfEvent -> updateEvent(owner.eventId) { it.copy(attachments = transform(it.attachments)) }

        is AttachmentOwner.OfHolding -> updateEvent(owner.eventId) { event ->
            val holding = event.holdings.firstOrNull { it.id == owner.holdingId }.orNotFound { "Holding ${owner.holdingId}" }
            val updated = holding.copy(attachments = transform(holding.attachments))
            event.copy(holdings = event.holdings.map { if (it.id == holding.id) updated else it })
        }
    }
}
