package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Money handed between parties to settle an event.

/**
 * A manual transfer or hand-over between parties (or a single settled suggestion). [together]: a suggestion's parties,
 * where a pair settling together is either partner; recorded between real people as
 * [com.jacekgajek.kapelusz.domain.SettleTogether.split] says, against the state it is applied to, as [transferId] and
 * `transferId-1` (ITERATION_5 §7).
 */
@Serializable
@SerialName("RecordTransfer")
data class RecordTransfer(
    override val eventId: Id,
    val transferId: Id,
    val from: Id,
    val to: Id,
    val amount: Money,
    val note: String? = null,
    val together: Boolean = false,
) : TransferOp

/**
 * Records the event's current suggested transfers, computed against the state the op is applied to.
 * [partyId] `null` = Settle All; otherwise only suggestions involving that party.
 */
@Serializable
@SerialName("SettleSuggestions")
data class SettleSuggestions(override val eventId: Id, val partyId: Id? = null) : TransferOp

@Serializable
@SerialName("RemoveTransfer")
data class RemoveTransfer(override val eventId: Id, val transferId: Id) : TransferOp
