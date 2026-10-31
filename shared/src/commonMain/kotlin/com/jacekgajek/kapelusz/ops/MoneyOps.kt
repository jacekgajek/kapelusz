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

// What an event pays out before the shares: cost refunds and the band fund's cut.

/** Sets how much of a cost this event refunds; zero removes the refund (defers the cost). */
@Serializable
@SerialName("SetRefund")
data class SetRefund(override val eventId: Id, val costId: Id, val amount: Money) : EventOp

@Serializable
@SerialName("SetFundAmount")
data class SetFundAmount(
    override val eventId: Id,
    /** Ignored when [percent] is set. */
    val amount: Money,
    /** A percentage of the profit, 0–100, instead of [amount] (ITERATION_7 §5.2); 0 is nothing. */
    val percent: Int? = null,
) : EventOp
