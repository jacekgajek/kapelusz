package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What an event pays out before the shares: cost refunds and the band fund's cut.

/** Sets how much of a cost this event refunds; zero removes the refund (defers the cost). */
@Serializable
@SerialName("SetRefund")
data class SetRefund(override val eventId: Id, val costId: Id, val amount: Money) : EventMoneyOp

@Serializable
@SerialName("SetFundAmount")
data class SetFundAmount(
    override val eventId: Id,
    /** Ignored when [percent] is set. */
    val amount: Money,
    /** A percentage of the profit, 0–100, instead of [amount] (I7 §5.2); 0 is nothing. */
    val percent: Int? = null,
) : EventMoneyOp
