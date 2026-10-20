package com.jacekgajek.artsplit.ops

import com.jacekgajek.artsplit.domain.CostLedger
import com.jacekgajek.artsplit.domain.SettlementCalculator
import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Cost
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.model.FundEntry
import com.jacekgajek.artsplit.model.Guest
import com.jacekgajek.artsplit.model.Holding
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.MAX_ATTACHMENTS
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.Refund
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.model.Transfer

/** Who applies an operation, and when. [memberId] is null for server-internal (system) operations. */
data class OpContext(val opId: Id, val timestamp: Long, val memberId: Id?)

/**
 * Pure state transitions with data validation. Permissions are checked separately by
 * [com.jacekgajek.artsplit.auth.Permissions]. Throws [RejectedException] for invalid operations.
 */
object Reducer {

    fun apply(band: Band, op: Operation, ctx: OpContext): Band {
        val result = when (op) {
            is RenameBand -> band.copy(name = op.name.validName("Band name"))
            is AddMember -> addMember(band, op)
            is RenameMember -> band.updateMember(op.memberId) { it.copy(name = op.name.validName("Member name")) }
            is MoveMember -> moveMember(band, op)
            is SetMemberArchived -> setMemberArchived(band, op)
            is DeleteMember -> deleteMember(band, op)
            is SetMemberRole -> setMemberRole(band, op)
            is LinkUser -> linkUser(band, op)
            is UnlinkUser -> unlinkUser(band, op)
            is SetTreasurer -> setTreasurer(band, op)
            is TransferOwnership -> transferOwnership(band, op)

            is CreateEvent -> createEvent(band, op)
            is UpdateEvent -> band.updateEvent(op.eventId) { event ->
                event.copy(
                    name = op.name?.value?.validName("Event name") ?: event.name,
                    date = op.date?.value ?: event.date,
                    location = if (op.location != null) op.location.value.blankToNull() else event.location,
                    notes = if (op.notes != null) op.notes.value.blankToNull() else event.notes,
                )
            }
            is SetEventArchived -> band.updateEvent(op.eventId) { it.copy(archived = op.archived) }
            is DeleteEvent -> deleteEvent(band, op)
            is AddParticipant -> addParticipant(band, op)
            is AddGuest -> addGuest(band, op)
            is RenameGuest -> band.updateEvent(op.eventId) { event ->
                event.guest(op.guestId).orNotFound { "Guest ${op.guestId}" }
                event.copy(guests = event.guests.map { if (it.id == op.guestId) it.copy(name = op.name.validName("Guest name")) else it })
            }
            is PromoteGuest -> promoteGuest(band, op)
            is SetWeight -> band.updateEvent(op.eventId) { event ->
                event.participant(op.partyId).orNotFound { "Participant ${op.partyId}" }
                validateWeight(op.weight)
                event.copy(participants = event.participants.map { if (it.partyId == op.partyId) it.copy(weight = op.weight) else it })
            }
            is RemoveParticipant -> removeParticipant(band, op)

            is AddHolding -> band.updateEvent(op.eventId) { event ->
                invalidIf(event.holdings.any { it.id == op.holdingId }) { "Holding ${op.holdingId} already exists" }
                invalidIf(event.participant(op.partyId) == null) { "Only participants can hold event money" }
                validatePositive(op.amount, "Holding amount")
                event.copy(holdings = event.holdings + Holding(op.holdingId, op.partyId, op.amount, op.source, op.note.blankToNull()))
            }
            is UpdateHolding -> band.updateEvent(op.eventId) { event ->
                event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
                op.amount?.let { validatePositive(it.value, "Holding amount") }
                event.copy(
                    holdings = event.holdings.map { holding ->
                        if (holding.id != op.holdingId) return@map holding
                        holding.copy(
                            amount = op.amount?.value ?: holding.amount,
                            source = op.source?.value ?: holding.source,
                            note = if (op.note != null) op.note.value.blankToNull() else holding.note,
                        )
                    },
                )
            }
            is RemoveHolding -> band.updateEvent(op.eventId) { event ->
                event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
                event.copy(holdings = event.holdings.filterNot { it.id == op.holdingId })
            }

            is SetRefund -> setRefund(band, op)
            is SetFundAmount -> band.updateEvent(op.eventId) { event ->
                invalidIf(!op.amount.isZero && band.treasurerMemberId == null) { "Choose a treasurer before using the band fund" }
                event.copy(fundAmount = op.amount)
            }
            is AddFundEntry -> addFundEntry(band, op)
            is UpdateFundEntry -> updateFundEntry(band, op)
            is RemoveFundEntry -> {
                band.fundEntries.firstOrNull { it.id == op.entryId }.orNotFound { "Fund entry ${op.entryId}" }
                band.copy(fundEntries = band.fundEntries.filterNot { it.id == op.entryId })
            }

            is RecordTransfer -> band.updateEvent(op.eventId) { event ->
                invalidIf(event.transfers.any { it.id == op.transferId }) { "Transfer ${op.transferId} already exists" }
                validateTransferParty(band, event, op.from)
                validateTransferParty(band, event, op.to)
                invalidIf(op.from == op.to) { "Cannot transfer to the same party" }
                validatePositive(op.amount, "Transfer amount")
                event.copy(transfers = event.transfers + Transfer(op.transferId, op.from, op.to, op.amount, op.note.blankToNull(), ctx.timestamp, ctx.memberId))
            }
            is SettleSuggestions -> settleSuggestions(band, op, ctx)
            is RemoveTransfer -> band.updateEvent(op.eventId) { event ->
                event.transfers.firstOrNull { it.id == op.transferId }.orNotFound { "Transfer ${op.transferId}" }
                event.copy(transfers = event.transfers.filterNot { it.id == op.transferId })
            }

            is AddCost -> addCost(band, op, ctx)
            is UpdateCost -> updateCost(band, op)
            is DeleteCost -> deleteCost(band, op)
            is AddAttachment -> band.updateCost(op.costId) { cost ->
                invalidIf(cost.attachments.size >= MAX_ATTACHMENTS) { "A cost can have at most $MAX_ATTACHMENTS attachments" }
                invalidIf(cost.attachments.any { it.id == op.attachment.id }) { "Attachment ${op.attachment.id} already exists" }
                cost.copy(attachments = cost.attachments + op.attachment)
            }
            is RemoveAttachment -> band.updateCost(op.costId) { cost ->
                cost.attachments.firstOrNull { it.id == op.attachmentId }.orNotFound { "Attachment ${op.attachmentId}" }
                cost.copy(attachments = cost.attachments.filterNot { it.id == op.attachmentId })
            }
        }
        return op.eventId
            ?.takeIf { op !is DeleteEvent }
            ?.let { eventId -> result.updateEvent(eventId) { it.copy(updatedAt = ctx.timestamp) } }
            ?: result
    }

    // ---- roster ----

    private fun addMember(band: Band, op: AddMember): Band {
        invalidIf(band.member(op.memberId) != null) { "Member ${op.memberId} already exists" }
        return band.copy(members = band.members + Member(op.memberId, op.name.validName("Member name")))
    }

    private fun moveMember(band: Band, op: MoveMember): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.toIndex !in band.members.indices) { "Index ${op.toIndex} out of range" }
        val others = band.members.filterNot { it.id == op.memberId }
        return band.copy(members = others.take(op.toIndex) + member + others.drop(op.toIndex))
    }

    private fun setMemberArchived(band: Band, op: SetMemberArchived): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.archived && member.role == Role.OWNER) { "The owner cannot be archived" }
        invalidIf(op.archived && band.treasurerMemberId == op.memberId) { "Choose another treasurer first" }
        return band.updateMember(op.memberId) { it.copy(archived = op.archived) }
    }

    private fun deleteMember(band: Band, op: DeleteMember): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        conflictIf(member.userId != null) { "Unlink the user before deleting the member" }
        conflictIf(band.treasurerMemberId == member.id) { "Choose another treasurer first" }
        conflictIf(band.costs.any { it.memberId == member.id }) { "Member has costs; archive instead" }
        conflictIf(band.events.any { it.participant(member.id) != null }) { "Member took part in events; archive instead" }
        return band.copy(members = band.members.filterNot { it.id == member.id })
    }

    private fun setMemberRole(band: Band, op: SetMemberRole): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.role == Role.OWNER) { "Use ownership transfer to make someone the owner" }
        invalidIf(member.role == Role.OWNER) { "The owner's role cannot be changed" }
        return band.updateMember(op.memberId) { it.copy(role = op.role) }
    }

    private fun linkUser(band: Band, op: LinkUser): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        conflictIf(member.userId != null) { "Member is already linked to a user" }
        conflictIf(band.memberOfUser(op.userId) != null) { "User is already a member of this band" }
        invalidIf(op.role == Role.OWNER) { "Invites cannot grant ownership" }
        return band.updateMember(op.memberId) { it.copy(userId = op.userId, role = op.role, archived = false) }
    }

    private fun unlinkUser(band: Band, op: UnlinkUser): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(member.role == Role.OWNER) { "Transfer ownership before unlinking the owner" }
        return band.updateMember(op.memberId) { it.copy(userId = null, role = Role.MEMBER) }
    }

    private fun setTreasurer(band: Band, op: SetTreasurer): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(member.archived) { "An archived member cannot hold the band fund" }
        return band.copy(treasurerMemberId = member.id)
    }

    private fun transferOwnership(band: Band, op: TransferOwnership): Band {
        val target = band.member(op.toMemberId).orNotFound { "Member ${op.toMemberId}" }
        invalidIf(target.userId == null || target.role != Role.ADMIN) { "Ownership can only go to a linked admin" }
        val currentOwner = band.owner
        return band.copy(
            members = band.members.map {
                when (it.id) {
                    target.id -> it.copy(role = Role.OWNER)
                    currentOwner?.id -> it.copy(role = Role.ADMIN)
                    else -> it
                }
            },
        )
    }

    // ---- events ----

    private fun createEvent(band: Band, op: CreateEvent): Band {
        invalidIf(band.event(op.eventId) != null) { "Event ${op.eventId} already exists" }
        val guestIds = op.guests.map { it.id }.toSet()
        invalidIf(guestIds.size != op.guests.size) { "Duplicate guest ids" }
        invalidIf(op.participants.map { it.partyId }.toSet().size != op.participants.size) { "Duplicate participants" }
        op.participants.forEach { participant ->
            validateWeight(participant.weight)
            if (participant.partyId !in guestIds) validateActiveMember(band, participant.partyId)
        }
        val event = Event(
            id = op.eventId,
            name = op.name.validName("Event name"),
            date = op.date,
            location = op.location.blankToNull(),
            notes = op.notes.blankToNull(),
            guests = op.guests.map { it.copy(name = it.name.validName("Guest name")) },
            participants = op.participants,
        )
        val created = band.copy(events = band.events + event)
        return op.participants.fold(created) { acc, participant -> includeOwnCosts(acc, op.eventId, participant.partyId) }
    }

    private fun deleteEvent(band: Band, op: DeleteEvent): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        conflictIf(event.transfers.isNotEmpty()) { "Event has recorded transfers; archive it instead" }
        return band.copy(
            events = band.events.filterNot { it.id == event.id },
            costs = band.costs.map { if (it.eventId == event.id) it.copy(eventId = null) else it },
        )
    }

    private fun addParticipant(band: Band, op: AddParticipant): Band {
        validateActiveMember(band, op.memberId)
        validateWeight(op.weight)
        val updated = band.updateEvent(op.eventId) { event ->
            invalidIf(event.participant(op.memberId) != null) { "Member already takes part" }
            event.copy(participants = event.participants + Participant(op.memberId, op.weight))
        }
        return includeOwnCosts(updated, op.eventId, op.memberId)
    }

    private fun addGuest(band: Band, op: AddGuest): Band = band.updateEvent(op.eventId) { event ->
        invalidIf(event.guest(op.guestId) != null || band.member(op.guestId) != null) { "Id ${op.guestId} already in use" }
        validateWeight(op.weight)
        event.copy(
            guests = event.guests + Guest(op.guestId, op.name.validName("Guest name")),
            participants = event.participants + Participant(op.guestId, op.weight),
        )
    }

    private fun promoteGuest(band: Band, op: PromoteGuest): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        val guest = event.guest(op.guestId).orNotFound { "Guest ${op.guestId}" }
        invalidIf(band.member(op.memberId) != null) { "Member ${op.memberId} already exists" }
        fun Id.repoint() = if (this == guest.id) op.memberId else this
        val promoted = event.copy(
            guests = event.guests.filterNot { it.id == guest.id },
            participants = event.participants.map { it.copy(partyId = it.partyId.repoint()) },
            holdings = event.holdings.map { it.copy(partyId = it.partyId.repoint()) },
            transfers = event.transfers.map { it.copy(from = it.from.repoint(), to = it.to.repoint()) },
        )
        return band.copy(
            members = band.members + Member(op.memberId, guest.name),
            events = band.events.map { if (it.id == event.id) promoted else it },
        )
    }

    private fun removeParticipant(band: Band, op: RemoveParticipant): Band = band.updateEvent(op.eventId) { event ->
        event.participant(op.partyId).orNotFound { "Participant ${op.partyId}" }
        conflictIf(event.holdings.any { it.partyId == op.partyId }) { "Participant holds event money; remove it first" }
        conflictIf(event.transfers.any { it.from == op.partyId || it.to == op.partyId }) { "Participant has recorded transfers" }
        conflictIf(event.refunds.any { band.cost(it.costId)?.memberId == op.partyId }) { "Participant has refunds here; defer them first" }
        event.copy(
            participants = event.participants.filterNot { it.partyId == op.partyId },
            guests = event.guests.filterNot { it.id == op.partyId },
        )
    }

    // ---- refunds, fund, transfers ----

    private fun setRefund(band: Band, op: SetRefund): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        return band.updateEvent(op.eventId) { event ->
            invalidIf(op.amount.isNegative) { "Refund cannot be negative" }
            if (op.amount.isZero) return@updateEvent event.copy(refunds = event.refunds.filterNot { it.costId == cost.id })
            invalidIf(!CostLedger.isRefundableAt(cost, event)) { "Costs can only be refunded at events their owner takes part in" }
            val max = CostLedger.maxRefundAt(band, cost, event)
            invalidIf(op.amount > max) { "Refund ${op.amount} exceeds outstanding $max" }
            val refunds = if (event.refundOf(cost.id) != null) {
                event.refunds.map { if (it.costId == cost.id) it.copy(amount = op.amount) else it }
            } else {
                event.refunds + Refund(cost.id, op.amount)
            }
            event.copy(refunds = refunds)
        }
    }

    private fun addFundEntry(band: Band, op: AddFundEntry): Band {
        invalidIf(band.treasurerMemberId == null) { "Choose a treasurer before using the band fund" }
        invalidIf(band.fundEntries.any { it.id == op.entryId }) { "Fund entry ${op.entryId} already exists" }
        invalidIf(op.amount.isZero) { "Fund entry amount cannot be zero" }
        op.costId?.let { costId ->
            val cost = band.cost(costId).orNotFound { "Cost $costId" }
            invalidIf(!op.amount.isNegative) { "A cost refund from the fund must be negative" }
            invalidIf(-op.amount > CostLedger.outstanding(band, cost)) { "Refund exceeds the outstanding amount" }
        }
        return band.copy(fundEntries = band.fundEntries + FundEntry(op.entryId, op.date, op.description.validName("Description"), op.amount, op.costId))
    }

    private fun updateFundEntry(band: Band, op: UpdateFundEntry): Band {
        val entry = band.fundEntries.firstOrNull { it.id == op.entryId }.orNotFound { "Fund entry ${op.entryId}" }
        val updated = entry.copy(
            date = op.date?.value ?: entry.date,
            description = op.description?.value?.validName("Description") ?: entry.description,
            amount = op.amount?.value ?: entry.amount,
        )
        invalidIf(updated.amount.isZero) { "Fund entry amount cannot be zero" }
        updated.costId?.let { costId ->
            val cost = band.cost(costId).orNotFound { "Cost $costId" }
            invalidIf(!updated.amount.isNegative) { "A cost refund from the fund must be negative" }
            invalidIf(-updated.amount > CostLedger.outstanding(band, cost) - entry.amount) { "Refund exceeds the outstanding amount" }
        }
        return band.copy(fundEntries = band.fundEntries.map { if (it.id == entry.id) updated else it })
    }

    private fun settleSuggestions(band: Band, op: SettleSuggestions, ctx: OpContext): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        val settlement = SettlementCalculator.settle(band, event)
        conflictIf(!settlement.canSettle) { "Event cannot be settled (status ${settlement.status}, problems ${settlement.problems})" }
        val suggestions = op.partyId?.let { settlement.suggestionsFor(it) } ?: settlement.suggestions
        val transfers = suggestions.mapIndexed { index, suggestion ->
            Transfer("${ctx.opId}-$index", suggestion.from, suggestion.to, suggestion.amount, null, ctx.timestamp, ctx.memberId)
        }
        return band.updateEvent(event.id) { it.copy(transfers = it.transfers + transfers) }
    }

    private fun validateTransferParty(band: Band, event: Event, partyId: Id) {
        if (partyId == BAND_FUND) {
            invalidIf(band.treasurerMemberId == null) { "Choose a treasurer before using the band fund" }
        } else {
            invalidIf(event.participant(partyId) == null) { "$partyId does not take part in the event" }
        }
    }

    // ---- costs ----

    private fun addCost(band: Band, op: AddCost, ctx: OpContext): Band {
        invalidIf(band.cost(op.costId) != null) { "Cost ${op.costId} already exists" }
        validateActiveMember(band, op.memberId)
        validatePositive(op.amount, "Cost amount")
        op.incurredForEventId?.let { band.event(it).orNotFound { "Event $it" } }
        val cost = Cost(
            id = op.costId,
            memberId = op.memberId,
            amount = op.amount,
            date = op.date,
            eventId = op.incurredForEventId,
            category = op.category,
            description = op.description.blankToNull(),
            createdAt = ctx.timestamp,
        )
        val added = band.copy(costs = band.costs + cost)
        return op.incurredForEventId?.let { includeOwnCosts(added, it, op.memberId) } ?: added
    }

    private fun updateCost(band: Band, op: UpdateCost): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        op.incurredForEventId?.value?.let { band.event(it).orNotFound { "Event $it" } }
        val newAmount = op.amount?.value ?: cost.amount
        validatePositive(newAmount, "Cost amount")
        var result = band.updateCost(cost.id) {
            it.copy(
                amount = newAmount,
                date = op.date?.value ?: it.date,
                eventId = if (op.incurredForEventId != null) op.incurredForEventId.value else it.eventId,
                category = if (op.category != null) op.category.value else it.category,
                description = if (op.description != null) op.description.value.blankToNull() else it.description,
            )
        }
        // A refund that covered the whole remainder at the cost's own event keeps covering it.
        val ownEvent = cost.eventId?.let { band.event(it) }
        val ownRefund = ownEvent?.refundOf(cost.id)
        if (ownEvent != null && ownRefund != null && newAmount != cost.amount) {
            val refundedElsewhere = CostLedger.refunded(band, cost.id) - ownRefund.amount
            if (ownRefund.amount == cost.amount - refundedElsewhere) {
                val adjusted = newAmount - refundedElsewhere
                invalidIf(adjusted.isNegative) { "Cost amount is below what was already refunded" }
                result = result.updateEvent(ownEvent.id) { event ->
                    event.copy(refunds = event.refunds.map { if (it.costId == cost.id) it.copy(amount = adjusted) else it })
                }
            }
        }
        invalidIf(newAmount < CostLedger.refunded(result, cost.id)) { "Cost amount is below what was already refunded" }
        return result
    }

    private fun deleteCost(band: Band, op: DeleteCost): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        conflictIf(band.fundEntries.any { it.costId == cost.id }) { "Cost was refunded from the band fund" }
        return band.copy(
            costs = band.costs.filterNot { it.id == cost.id },
            events = band.events.map { event -> event.copy(refunds = event.refunds.filterNot { it.costId == cost.id }) },
        )
    }

    /** Pre-includes, in full, the member's outstanding costs incurred for this event. */
    private fun includeOwnCosts(band: Band, eventId: Id, memberId: Id): Band {
        val event = band.event(eventId) ?: return band
        if (event.participant(memberId) == null) return band
        val newRefunds = band.costs
            .filter { it.memberId == memberId && it.eventId == eventId && event.refundOf(it.id) == null }
            .mapNotNull { cost -> CostLedger.outstanding(band, cost).takeIf { it.isPositive }?.let { Refund(cost.id, it) } }
        return if (newRefunds.isEmpty()) band else band.updateEvent(eventId) { it.copy(refunds = it.refunds + newRefunds) }
    }

    // ---- helpers ----

    private fun validateActiveMember(band: Band, memberId: Id) {
        val member = band.member(memberId).orNotFound { "Member $memberId" }
        invalidIf(member.archived) { "Member ${member.name} is archived" }
    }

    private fun validateWeight(weight: Int) = invalidIf(weight !in 0..10_000) { "Share weight must be between 0 and 10000" }

    private fun validatePositive(amount: Money, what: String) = invalidIf(!amount.isPositive) { "$what must be positive" }

    private fun String.validName(what: String): String = trim().also { invalidIf(it.isEmpty()) { "$what cannot be empty" } }

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private inline fun Band.updateEvent(eventId: Id, transform: (Event) -> Event): Band {
        val event = event(eventId).orNotFound { "Event $eventId" }
        val updated = transform(event)
        return copy(events = events.map { if (it.id == eventId) updated else it })
    }

    private inline fun Band.updateMember(memberId: Id, transform: (Member) -> Member): Band {
        val member = member(memberId).orNotFound { "Member $memberId" }
        val updated = transform(member)
        return copy(members = members.map { if (it.id == memberId) updated else it })
    }

    private inline fun Band.updateCost(costId: Id, transform: (Cost) -> Cost): Band {
        val cost = cost(costId).orNotFound { "Cost $costId" }
        val updated = transform(cost)
        return copy(costs = costs.map { if (it.id == costId) updated else it })
    }
}

