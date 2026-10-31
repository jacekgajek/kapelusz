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

/** Patch field: `null` means "leave unchanged", `Change(x)` means "set to x" (x itself may be null). */
@Serializable
data class Change<out T>(val value: T)

/** An operation as queued by a client and pushed to the server. */
@Serializable
data class OpEnvelope(
    val opId: Id,
    val clientId: String,
    val seq: Long,
    val timestamp: Long,
    val op: Operation,
)

/** Every state change is one of these. Ops carry entity ids and only the changed fields. */
@Serializable
sealed interface Operation {
    /** Event the operation touches, used to stamp [com.jacekgajek.kapelusz.model.Event.updatedAt]. */
    val eventId: Id? get() = null
}

// The kinds of operation, by what they change. Only the leaves are stored and sent (each has its own `type`); these
// groups are for the code: the reducer, the permissions and the UI dispatch on them, and a new operation added to a
// group is handled wherever the group is.

/** The band itself: name, fund settings, agenda template, ownership, deletion. Leaders; the owner for ownership and deletion. */
@Serializable
sealed interface BandOp : Operation

/** One member of the roster. Leaders; members their own payment details. */
@Serializable
sealed interface RosterOp : Operation {
    val memberId: Id
}

/** Settling together (ITERATION_5 §7): each member for themselves. */
@Serializable
sealed interface PairOp : Operation

/** Something inside one event. Leaders, unless a sub-group says otherwise. */
@Serializable
sealed interface EventOp : Operation {
    override val eventId: Id
}

/** The event's line-up: who takes part, with what share. Leaders. */
@Serializable
sealed interface LineupOp : EventOp

/** One received-money entry (or awaited fee) of an event. Leaders, or a member for their own. */
@Serializable
sealed interface HoldingOp : EventOp {
    val holdingId: Id
}

/** Transfers that settle an event. Leaders, or a party to the transfer for their own. */
@Serializable
sealed interface TransferOp : EventOp

/** One cost. A member their own, leaders anyone's. */
@Serializable
sealed interface CostOp : Operation {
    val costId: Id
}

/** One band fund entry. Leaders; the treasurer money paid in. */
@Serializable
sealed interface FundOp : Operation {
    val entryId: Id
}

/** A file on a cost, a received-money entry or an event: whoever may edit what it is on. */
@Serializable
sealed interface AttachmentOp : Operation {
    val owner: AttachmentOwner

    override val eventId: Id? get() = owner.eventId
}

/**
 * An operation of a kind this version doesn't know (ITERATION_8 §2): one removed since (`MoveMember`), or one from a
 * newer version. Read as this, so History and a phone's unsent changes still load; never applied. Keeps the event it
 * was about, so an event's History still lists it.
 */
@Serializable
@SerialName("Unknown")
data class UnknownOperation(override val eventId: Id? = null) : Operation

/**
 * Undoes the operation [undoneOpId] ([original]) by putting back what it touched ([change]). [entry] names the
 * history entry it is in; the server takes [original] and [change] from there, so a client cannot make them up.
 */
@Serializable
@SerialName("Undo")
data class Undo(val undoneOpId: Id, val original: Operation, val change: BandChange, val entry: String? = null) : Operation
