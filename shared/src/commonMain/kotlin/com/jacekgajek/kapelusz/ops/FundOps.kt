package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Money paid into the band fund, or refunded from it.

@Serializable
@SerialName("AddFundEntry")
data class AddFundEntry(
    override val entryId: Id,
    val date: LocalDate,
    val description: String,
    val amount: Money,
    val costId: Id? = null,
    /** Who paid it in: a member, or null for someone outside the band (I7 §5.1). */
    val paidBy: Id? = null,
) : FundOp

@Serializable
@SerialName("UpdateFundEntry")
data class UpdateFundEntry(
    override val entryId: Id,
    val date: Change<LocalDate>? = null,
    val description: Change<String>? = null,
    val amount: Change<Money>? = null,
    val paidBy: Change<Id?>? = null,
) : FundOp

@Serializable
@SerialName("RemoveFundEntry")
data class RemoveFundEntry(override val entryId: Id) : FundOp
