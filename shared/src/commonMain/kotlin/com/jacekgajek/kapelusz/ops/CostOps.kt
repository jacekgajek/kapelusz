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

// Costs a member paid for the band.

@Serializable
@SerialName("AddCost")
data class AddCost(
    override val costId: Id,
    val memberId: Id,
    val amount: Money,
    val date: LocalDate,
    val incurredForEventId: Id? = null,
    val category: CostCategory? = null,
    val description: String? = null,
) : CostOp

@Serializable
@SerialName("UpdateCost")
data class UpdateCost(
    override val costId: Id,
    val amount: Change<Money>? = null,
    val date: Change<LocalDate>? = null,
    val incurredForEventId: Change<Id?>? = null,
    val category: Change<CostCategory?>? = null,
    val description: Change<String?>? = null,
) : CostOp

@Serializable
@SerialName("DeleteCost")
data class DeleteCost(override val costId: Id) : CostOp
