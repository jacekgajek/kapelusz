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

// Money received for an event, and fees still awaited from the organiser.

@Serializable
@SerialName("AddHolding")
data class AddHolding(
    override val eventId: Id,
    override val holdingId: Id,
    val partyId: Id,
    val amount: Money,
    val source: IncomeSource,
    val note: String? = null,
) : HoldingOp

/**
 * An awaited fee ([com.jacekgajek.kapelusz.model.ORGANISER]) came in, in full or [amount] of it (ITERATION_4 §2.2):
 * that much becomes [partyId]'s collected money, as entry [receivedId]; the rest stays awaited.
 */
@Serializable
@SerialName("ReceiveAwaited")
data class ReceiveAwaited(
    override val eventId: Id,
    override val holdingId: Id,
    val receivedId: Id,
    val partyId: Id,
    val amount: Money,
) : HoldingOp

@Serializable
@SerialName("UpdateHolding")
data class UpdateHolding(
    override val eventId: Id,
    override val holdingId: Id,
    val amount: Change<Money>? = null,
    val source: Change<IncomeSource>? = null,
    val note: Change<String?>? = null,
) : HoldingOp

@Serializable
@SerialName("RemoveHolding")
data class RemoveHolding(override val eventId: Id, override val holdingId: Id) : HoldingOp
