package com.jacekgajek.artsplit.ops

import com.jacekgajek.artsplit.model.AgendaTemplateItem
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.Attachment
import com.jacekgajek.artsplit.model.CostCategory
import com.jacekgajek.artsplit.model.DEFAULT_WEIGHT
import com.jacekgajek.artsplit.model.Guest
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.Role
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
    /** Event the operation touches, used to stamp [com.jacekgajek.artsplit.model.Event.updatedAt]. */
    val eventId: Id? get() = null
}

// ---- Band & roster (admin) ----

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

@Serializable @SerialName("SetMemberRole")
data class SetMemberRole(val memberId: Id, val role: Role) : Operation

/** Applied by the server when a user accepts an invite. */
@Serializable @SerialName("LinkUser")
data class LinkUser(val memberId: Id, val userId: String, val role: Role) : Operation

@Serializable @SerialName("UnlinkUser")
data class UnlinkUser(val memberId: Id) : Operation

@Serializable @SerialName("SetTreasurer")
data class SetTreasurer(val memberId: Id) : Operation

/** Turns the band fund off (only while it holds nothing); choosing a holder with [SetTreasurer] turns it on again. */
@Serializable @SerialName("DisableBandFund")
data object DisableBandFund : Operation

@Serializable @SerialName("TransferOwnership")
data class TransferOwnership(val toMemberId: Id) : Operation

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
    /** The band's agenda template at creation time, times not set yet (VERSION_2 §4.1). */
    val agenda: List<AgendaTime> = emptyList(),
) : Operation

/** Replaces an event's agenda times (lead+); last write wins, like other event details. */
@Serializable @SerialName("SetEventAgenda")
data class SetEventAgenda(override val eventId: Id, val agenda: List<AgendaTime>) : Operation

/** The agenda times new events get (admin+); existing events keep theirs. */
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
data class AddParticipant(override val eventId: Id, val memberId: Id, val weight: Int = DEFAULT_WEIGHT) : Operation

@Serializable @SerialName("AddGuest")
data class AddGuest(override val eventId: Id, val guestId: Id, val name: String, val weight: Int = DEFAULT_WEIGHT) :
    Operation

@Serializable @SerialName("RenameGuest")
data class RenameGuest(override val eventId: Id, val guestId: Id, val name: String) : Operation

/** Turns a guest into a new roster member, re-pointing all the guest's entries in this event. */
@Serializable @SerialName("PromoteGuest")
data class PromoteGuest(override val eventId: Id, val guestId: Id, val memberId: Id) : Operation

@Serializable @SerialName("SetWeight")
data class SetWeight(override val eventId: Id, val partyId: Id, val weight: Int) : Operation

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
data class SetFundAmount(override val eventId: Id, val amount: Money) : Operation

@Serializable @SerialName("AddFundEntry")
data class AddFundEntry(
    val entryId: Id,
    val date: LocalDate,
    val description: String,
    val amount: Money,
    val costId: Id? = null,
) : Operation

@Serializable @SerialName("UpdateFundEntry")
data class UpdateFundEntry(
    val entryId: Id,
    val date: Change<LocalDate>? = null,
    val description: Change<String>? = null,
    val amount: Change<Money>? = null,
) : Operation

@Serializable @SerialName("RemoveFundEntry")
data class RemoveFundEntry(val entryId: Id) : Operation

// ---- Transfers ----

/** A manual transfer or hand-over between parties (or a single settled suggestion). */
@Serializable @SerialName("RecordTransfer")
data class RecordTransfer(
    override val eventId: Id,
    val transferId: Id,
    val from: Id,
    val to: Id,
    val amount: Money,
    val note: String? = null,
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

@Serializable @SerialName("AddAttachment")
data class AddAttachment(val costId: Id, val attachment: Attachment) : Operation

@Serializable @SerialName("RemoveAttachment")
data class RemoveAttachment(val costId: Id, val attachmentId: Id) : Operation
