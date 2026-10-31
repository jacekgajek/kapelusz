package com.jacekgajek.kapelusz.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Files on one cost or one collected entry. */
const val MAX_ATTACHMENTS = 5

/** Files on an event itself (I4 §2.1). */
const val MAX_EVENT_ATTACHMENTS = 10

/** What an attachment belongs to (I4 §2.1): a cost's receipt, a collected entry's contract, or the gig's files. */
@Serializable
sealed interface AttachmentOwner {
    /** The event it is part of, if any: archived events are read-only. */
    val eventId: Id? get() = null

    @Serializable
    @SerialName("cost")
    data class OfCost(val costId: Id) : AttachmentOwner

    @Serializable
    @SerialName("holding")
    data class OfHolding(override val eventId: Id, val holdingId: Id) : AttachmentOwner

    @Serializable
    @SerialName("event")
    data class OfEvent(override val eventId: Id) : AttachmentOwner
}

/** The attachments of [owner], or null when it does not exist. */
fun Band.attachmentsOf(owner: AttachmentOwner): List<Attachment>? = when (owner) {
    is AttachmentOwner.OfCost -> cost(owner.costId)?.attachments
    is AttachmentOwner.OfHolding -> event(owner.eventId)?.holdings?.firstOrNull { it.id == owner.holdingId }?.attachments
    is AttachmentOwner.OfEvent -> event(owner.eventId)?.attachments
}

/** Every attachment in the band, wherever it is. */
fun Band.allAttachments(): List<Attachment> = costs.flatMap { it.attachments } + events.flatMap { event -> event.attachments + event.holdings.flatMap { it.attachments } }

/** Money a member spent on behalf of the band. Belongs to the member; events repay it via [Refund]s. */
@Serializable
data class Cost(
    val id: Id,
    val memberId: Id,
    val amount: Money,
    val date: LocalDate,
    /** Event the cost was incurred for, if any. A lead's cost is pre-included there (I4 §1.2). */
    val eventId: Id? = null,
    val category: CostCategory? = null,
    val description: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = 0,
)

@Serializable
enum class CostCategory { TRAVEL, ACCOMMODATION, EQUIPMENT, FOOD, PROMOTION, OTHER }

@Serializable
data class Attachment(
    val id: Id,
    val kind: AttachmentKind,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
) {
    companion object {
        const val MAX_FILE_NAME = 120
    }
}

@Serializable
enum class AttachmentKind(val contentType: String, val extension: String, val maxBytes: Long) {
    /** Compressed on the device (longest edge 1600 px, JPEG), so well below the limit. */
    IMAGE("image/jpeg", "jpg", 3L * 1024 * 1024),
    PDF("application/pdf", "pdf", 10L * 1024 * 1024),
}

/**
 * Manual band fund entry (I7 §5.1): money paid in by [paidBy] (a member; null: from outside the band), or,
 * negative, the refund of [costId] from the fund. Money leaves the fund only to refund a cost.
 */
@Serializable
data class FundEntry(
    val id: Id,
    val date: LocalDate,
    val description: String,
    val amount: Money,
    val costId: Id? = null,
    val paidBy: Id? = null,
)
