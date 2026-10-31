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

// Money paid into the band fund, or refunded from it.

@Serializable
@SerialName("AddFundEntry")
data class AddFundEntry(
    override val entryId: Id,
    val date: LocalDate,
    val description: String,
    val amount: Money,
    val costId: Id? = null,
    /** Who paid it in: a member, or null for someone outside the band (ITERATION_7 §5.1). */
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
