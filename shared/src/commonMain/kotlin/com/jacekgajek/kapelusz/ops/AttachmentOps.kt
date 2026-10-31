package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Id
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Files on costs, received-money entries and events.

/** Attaches a file to a cost, a collected entry or an event ([owner]). */
@Serializable
@SerialName("AddAttachment")
data class AddAttachment(override val owner: AttachmentOwner, val attachment: Attachment) : AttachmentOp {
    constructor(costId: Id, attachment: Attachment) : this(AttachmentOwner.OfCost(costId), attachment)
}

@Serializable
@SerialName("RemoveAttachment")
data class RemoveAttachment(override val owner: AttachmentOwner, val attachmentId: Id) : AttachmentOp {
    constructor(costId: Id, attachmentId: Id) : this(AttachmentOwner.OfCost(costId), attachmentId)
}

/** Gives an attachment another file name. */
@Serializable
@SerialName("RenameAttachment")
data class RenameAttachment(override val owner: AttachmentOwner, val attachmentId: Id, val fileName: String) : AttachmentOp
