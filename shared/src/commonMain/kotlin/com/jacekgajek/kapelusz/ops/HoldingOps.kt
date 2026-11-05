package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Vat
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
    /** The net and the VAT rate (a whole percent) when [amount] is a gross with VAT in it. */
    val vat: Vat? = null,
) : HoldingOp

/**
 * An awaited fee ([com.jacekgajek.kapelusz.model.ORGANISER]) came in, whole: it becomes [partyId]'s collected money,
 * as entry [receivedId], with its VAT breakdown and files. [amount]: the fee as the sender saw it; a fee changed
 * meanwhile is refused, to be looked at again. Part of a fee: change the fee first.
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
    val vat: Change<Vat?>? = null,
) : HoldingOp

@Serializable
@SerialName("RemoveHolding")
data class RemoveHolding(override val eventId: Id, override val holdingId: Id) : HoldingOp
