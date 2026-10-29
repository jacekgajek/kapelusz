package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
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

// ---- Band & roster (leader) ----

@Serializable @SerialName("RenameBand")
data class RenameBand(val name: String) : Operation

@Serializable @SerialName("AddMember")
data class AddMember(val memberId: Id, val name: String) : Operation

@Serializable @SerialName("RenameMember")
data class RenameMember(val memberId: Id, val name: String) : Operation

@Serializable @SerialName("MoveMember")
data class MoveMember(val memberId: Id, val toIndex: Int) : Operation

@Serializable @SerialName("SetMemberArchived")
data class SetMemberArchived(val memberId: Id, val archived: Boolean) : Operation

@Serializable @SerialName("DeleteMember")
data class DeleteMember(val memberId: Id) : Operation

/**
 * A member's default share (ITERATION_4 §2.3): [weight], or [fixedShare] or [percent] (ITERATION_7 §2h) when set. Also
 * applied, in the same step, to their share in [events] (chosen by the user: events not settled where they still had
 * the old default).
 */
@Serializable @SerialName("SetMemberShare")
data class SetMemberShare(val memberId: Id, val weight: Int, val fixedShare: Money?, val events: List<Id> = emptyList(), val percent: Int? = null) :
    Operation

@Serializable @SerialName("SetMemberRole")
data class SetMemberRole(val memberId: Id, val role: Role) : Operation

/** Applied by the server when a user accepts an invite. */
@Serializable @SerialName("LinkUser")
data class LinkUser(val memberId: Id, val userId: String, val role: Role) : Operation

@Serializable @SerialName("UnlinkUser")
data class UnlinkUser(val memberId: Id) : Operation

/** The member's own BLIK phone number (ITERATION_5 §8); null removes it. */
@Serializable @SerialName("SetBlikPhone")
data class SetBlikPhone(val memberId: Id, val phone: String?) : Operation

@Serializable @SerialName("SetTreasurer")
data class SetTreasurer(val memberId: Id) : Operation

/** Turns the band fund off (only while it holds nothing); choosing a holder with [SetTreasurer] turns it on again. */
@Serializable @SerialName("DisableBandFund")
data object DisableBandFund : Operation

@Serializable @SerialName("TransferOwnership")
data class TransferOwnership(val toMemberId: Id) : Operation

// ---- Settling together (ITERATION_5 §7): each for themselves ----

/** [fromMemberId] invites [toMemberId] to settle together. */
@Serializable @SerialName("InviteToSettleTogether")
data class InviteToSettleTogether(val fromMemberId: Id, val toMemberId: Id) : Operation

/** The inviter takes the invitation back. */
@Serializable @SerialName("CancelSettleTogether")
data class CancelSettleTogether(val fromMemberId: Id, val toMemberId: Id) : Operation

/** The invited member says yes: from now on the two settle together. */
@Serializable @SerialName("AcceptSettleTogether")
data class AcceptSettleTogether(val fromMemberId: Id, val toMemberId: Id) : Operation

/** The invited member says no. */
@Serializable @SerialName("DeclineSettleTogether")
data class DeclineSettleTogether(val fromMemberId: Id, val toMemberId: Id) : Operation

/** [memberId] stops settling together with [partnerId]. */
@Serializable @SerialName("StopSettlingTogether")
data class StopSettlingTogether(val memberId: Id, val partnerId: Id) : Operation

// ---- Events (lead) ----

@Serializable @SerialName("CreateEvent")
data class CreateEvent(
    override val eventId: Id,
    val name: String,
    val date: LocalDate,
    val location: String? = null,
    val notes: String? = null,
    val guests: List<Guest> = emptyList(),
    val participants: List<Participant> = emptyList(),
    /** The band's agenda template at creation time, times not set yet (ITERATION_2 §4.1). */
    val agenda: List<AgendaTime> = emptyList(),
    /** The band's fund default at creation time (ITERATION_7 §2g); null: nothing to the fund. */
    val fund: FundCut? = null,
) : Operation

/** Replaces an event's agenda times (lead+); last write wins, like other event details. */
@Serializable @SerialName("SetEventAgenda")
data class SetEventAgenda(override val eventId: Id, val agenda: List<AgendaTime>) : Operation

/** The agenda times new events get (leader+); existing events keep theirs. */
@Serializable @SerialName("SetAgendaTemplate")
data class SetAgendaTemplate(val items: List<AgendaTemplateItem>) : Operation

@Serializable @SerialName("UpdateEvent")
data class UpdateEvent(
    override val eventId: Id,
    val name: Change<String>? = null,
    val date: Change<LocalDate>? = null,
    val location: Change<String?>? = null,
    val notes: Change<String?>? = null,
) : Operation

@Serializable @SerialName("SetEventArchived")
data class SetEventArchived(override val eventId: Id, val archived: Boolean) : Operation

@Serializable @SerialName("DeleteEvent")
data class DeleteEvent(override val eventId: Id) : Operation

@Serializable @SerialName("AddParticipant")
data class AddParticipant(
    override val eventId: Id,
    val memberId: Id,
    val weight: Int = DEFAULT_WEIGHT,
    val fixedShare: Money? = null,
    val percent: Int? = null,
) : Operation

@Serializable @SerialName("AddGuest")
data class AddGuest(
    override val eventId: Id,
    val guestId: Id,
    val name: String,
    val weight: Int = DEFAULT_WEIGHT,
    val fixedShare: Money? = null,
    val percent: Int? = null,
) : Operation

@Serializable @SerialName("RenameGuest")
data class RenameGuest(override val eventId: Id, val guestId: Id, val name: String) : Operation

@Serializable @SerialName("SetWeight")
data class SetWeight(override val eventId: Id, val partyId: Id, val weight: Int) : Operation

/**
 * A fixed amount the participant takes off the top instead of a share by weight (ITERATION_3 §2.4), or a [percent] of
 * the profit (ITERATION_7 §2h); neither goes back to the weight. [weight], when set, is applied in the same step, so
 * going back to a different weight is one change to undo.
 */
@Serializable @SerialName("SetFixedShare")
data class SetFixedShare(override val eventId: Id, val partyId: Id, val amount: Money?, val weight: Int? = null, val percent: Int? = null) :
    Operation

@Serializable @SerialName("RemoveParticipant")
data class RemoveParticipant(override val eventId: Id, val partyId: Id) : Operation

// ---- Holdings (lead, or member for themselves) ----

@Serializable @SerialName("AddHolding")
data class AddHolding(
    override val eventId: Id,
    val holdingId: Id,
    val partyId: Id,
    val amount: Money,
    val source: IncomeSource,
    val note: String? = null,
) : Operation

/**
 * An awaited fee ([com.jacekgajek.kapelusz.model.ORGANISER]) came in, in full or [amount] of it (ITERATION_4 §2.2):
 * that much becomes [partyId]'s collected money, as entry [receivedId]; the rest stays awaited.
 */
@Serializable @SerialName("ReceiveAwaited")
data class ReceiveAwaited(override val eventId: Id, val holdingId: Id, val receivedId: Id, val partyId: Id, val amount: Money) : Operation

@Serializable @SerialName("UpdateHolding")
data class UpdateHolding(
    override val eventId: Id,
    val holdingId: Id,
    val amount: Change<Money>? = null,
    val source: Change<IncomeSource>? = null,
    val note: Change<String?>? = null,
) : Operation

@Serializable @SerialName("RemoveHolding")
data class RemoveHolding(override val eventId: Id, val holdingId: Id) : Operation

// ---- Refunds & band fund (lead) ----

/** Sets how much of a cost this event refunds; zero removes the refund (defers the cost). */
@Serializable @SerialName("SetRefund")
data class SetRefund(override val eventId: Id, val costId: Id, val amount: Money) : Operation

@Serializable @SerialName("SetFundAmount")
data class SetFundAmount(
    override val eventId: Id,
    /** Ignored when [percent] is set. */
    val amount: Money,
    /** A percentage of the profit, 0–100, instead of [amount] (ITERATION_7 §2g); 0 is nothing. */
    val percent: Int? = null,
) : Operation

/** The band's fund default for new events (ITERATION_7 §2g), applied to [events] too, as a member's default share is. */
@Serializable @SerialName("SetFundDefault")
data class SetFundDefault(val cut: FundCut?, val events: List<Id> = emptyList()) : Operation

@Serializable @SerialName("AddFundEntry")
data class AddFundEntry(
    val entryId: Id,
    val date: LocalDate,
    val description: String,
    val amount: Money,
    val costId: Id? = null,
    /** Who paid it in: a member, or null for someone outside the band (ITERATION_7 §2e). */
    val paidBy: Id? = null,
) : Operation

@Serializable @SerialName("UpdateFundEntry")
data class UpdateFundEntry(
    val entryId: Id,
    val date: Change<LocalDate>? = null,
    val description: Change<String>? = null,
    val amount: Change<Money>? = null,
    val paidBy: Change<Id?>? = null,
) : Operation

@Serializable @SerialName("RemoveFundEntry")
data class RemoveFundEntry(val entryId: Id) : Operation

// ---- Transfers ----

/**
 * A manual transfer or hand-over between parties (or a single settled suggestion). [together]: a suggestion's parties,
 * where a pair settling together is either partner; recorded between real people as
 * [com.jacekgajek.kapelusz.domain.SettleTogether.split] says, against the state it is applied to, as [transferId] and
 * `transferId-1` (ITERATION_5 §7).
 */
@Serializable @SerialName("RecordTransfer")
data class RecordTransfer(
    override val eventId: Id,
    val transferId: Id,
    val from: Id,
    val to: Id,
    val amount: Money,
    val note: String? = null,
    val together: Boolean = false,
) : Operation

/**
 * Records the event's current suggested transfers, computed against the state the op is applied to.
 * [partyId] `null` = Settle All; otherwise only suggestions involving that party.
 */
@Serializable @SerialName("SettleSuggestions")
data class SettleSuggestions(override val eventId: Id, val partyId: Id? = null) : Operation

@Serializable @SerialName("RemoveTransfer")
data class RemoveTransfer(override val eventId: Id, val transferId: Id) : Operation

// ---- Costs (member for their own, lead for anyone's) ----

@Serializable @SerialName("AddCost")
data class AddCost(
    val costId: Id,
    val memberId: Id,
    val amount: Money,
    val date: LocalDate,
    val incurredForEventId: Id? = null,
    val category: CostCategory? = null,
    val description: String? = null,
) : Operation

@Serializable @SerialName("UpdateCost")
data class UpdateCost(
    val costId: Id,
    val amount: Change<Money>? = null,
    val date: Change<LocalDate>? = null,
    val incurredForEventId: Change<Id?>? = null,
    val category: Change<CostCategory?>? = null,
    val description: Change<String?>? = null,
) : Operation

@Serializable @SerialName("DeleteCost")
data class DeleteCost(val costId: Id) : Operation

/** Attaches a file to a cost, a collected entry or an event ([owner], ITERATION_4 §2.1). */
@Serializable @SerialName("AddAttachment")
data class AddAttachment(val owner: AttachmentOwner, val attachment: Attachment) : Operation {
    constructor(costId: Id, attachment: Attachment) : this(AttachmentOwner.OfCost(costId), attachment)

    override val eventId: Id? get() = owner.eventId
}

@Serializable @SerialName("RemoveAttachment")
data class RemoveAttachment(val owner: AttachmentOwner, val attachmentId: Id) : Operation {
    constructor(costId: Id, attachmentId: Id) : this(AttachmentOwner.OfCost(costId), attachmentId)

    override val eventId: Id? get() = owner.eventId
}

/** Gives an attachment another file name (ITERATION_4 §2.1). */
@Serializable @SerialName("RenameAttachment")
data class RenameAttachment(val owner: AttachmentOwner, val attachmentId: Id, val fileName: String) : Operation {
    override val eventId: Id? get() = owner.eventId
}

// ---- undo (ITERATION_2 §7.2) ----

/**
 * Undoes the operation [undoneOpId] ([original]) by putting back what it touched ([change]). [entry] names the
 * history entry it is in; the server takes [original] and [change] from there, so a client cannot make them up.
 */
@Serializable @SerialName("Undo")
data class Undo(val undoneOpId: Id, val original: Operation, val change: BandChange, val entry: String? = null) : Operation
