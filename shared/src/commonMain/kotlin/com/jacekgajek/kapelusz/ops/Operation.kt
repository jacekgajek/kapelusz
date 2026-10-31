package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Id
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Patch field: `null` means "leave unchanged", `Change(x)` means "set to x" (x itself may be null). */
@Serializable
data class Change<out T>(val value: T)

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

/** Settling together (I5 §7): each member for themselves. */
@Serializable
sealed interface PairOp : Operation

/** Something inside one event. Leaders, unless a sub-group says otherwise. */
@Serializable
sealed interface EventOp : Operation {
    override val eventId: Id
}

/** The event itself: its details, agenda, archiving, deletion. Leaders. */
@Serializable
sealed interface EventDetailsOp : EventOp

/** What the event pays out before the shares: cost refunds and the band fund's cut. Leaders. */
@Serializable
sealed interface EventMoneyOp : EventOp

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
 * An operation of a kind this version doesn't know (I8 §2): one removed since (`MoveMember`), or one from a
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
