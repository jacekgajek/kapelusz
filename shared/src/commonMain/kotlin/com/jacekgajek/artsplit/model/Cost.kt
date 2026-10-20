package com.jacekgajek.artsplit.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

const val MAX_ATTACHMENTS = 5

/** Money a member spent on behalf of the band. Belongs to the member; events repay it via [Refund]s. */
@Serializable
data class Cost(
    val id: Id,
    val memberId: Id,
    val amount: Money,
    val date: LocalDate,
    /** Event the cost was incurred for, if any. Its refund there is pre-included. */
    val eventId: Id? = null,
    val category: CostCategory? = null,
    val description: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = 0,
)

@Serializable
enum class CostCategory { TRAVEL, ACCOMMODATION, EQUIPMENT, FOOD, OTHER }

@Serializable
data class Attachment(
    val id: Id,
    val kind: AttachmentKind,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
)

@Serializable
enum class AttachmentKind { IMAGE, PDF }

/** Manual band fund entry. A negative entry linked to [costId] refunds that cost from the fund. */
@Serializable
data class FundEntry(
    val id: Id,
    val date: LocalDate,
    val description: String,
    val amount: Money,
    val costId: Id? = null,
)
