package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Files on costs, received-money entries and events (ITERATION_4 §2.1).

/** Attaches a file to a cost, a collected entry or an event ([owner], ITERATION_4 §2.1). */
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

/** Gives an attachment another file name (ITERATION_4 §2.1). */
@Serializable
@SerialName("RenameAttachment")
data class RenameAttachment(override val owner: AttachmentOwner, val attachmentId: Id, val fileName: String) : AttachmentOp
