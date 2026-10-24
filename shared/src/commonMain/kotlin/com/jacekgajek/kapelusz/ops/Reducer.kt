package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.BlikPhone
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.domain.Viewers
import com.jacekgajek.kapelusz.domain.MemberShares
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.MAX_ATTACHMENTS
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.model.allAttachments

/** Who applies an operation, and when. [memberId] is null for server-internal (system) operations. */
data class OpContext(val opId: Id, val timestamp: Long, val memberId: Id?)

/**
 * Pure state transitions with data validation. Permissions are checked separately by
 * [com.jacekgajek.kapelusz.auth.Permissions]. Throws [RejectedException] for invalid operations.
 */
object Reducer {

    fun apply(band: Band, op: Operation, ctx: OpContext): Band {
        // Archived events are read-only (ITERATION_3 R10): restoring and deleting them are the only changes.
        op.eventId?.let { band.event(it) }?.let { event ->
            conflictIf(event.archived && op !is SetEventArchived && op !is DeleteEvent, RejectionReason.ARCHIVED_EVENT) { "Event ${event.id} is archived" }
        }
        val result = when (op) {
            is RenameBand -> band.copy(name = op.name.validName("Band name"))
            is AddMember -> addMember(band, op)
            is RenameMember -> band.updateMember(op.memberId) { it.copy(name = op.name.validName("Member name")) }
            is MoveMember -> moveMember(band, op)
            is SetMemberArchived -> setMemberArchived(band, op)
            is DeleteMember -> deleteMember(band, op)
            is SetMemberRole -> setMemberRole(band, op)
            is SetMemberShare -> setMemberShare(band, op, ctx)
            is LinkUser -> linkUser(band, op)
            is UnlinkUser -> unlinkUser(band, op)
            is SetTreasurer -> setTreasurer(band, op)
            is SetBlikPhone -> {
                val phone = op.phone?.let { BlikPhone.normalize(it) }
                invalidIf(op.phone != null && phone == null, RejectionReason.INVALID_PHONE) { "Not a phone number" }
                band.updateMember(op.memberId) { it.copy(blikPhone = phone) }
            }
            is DisableBandFund -> {
                conflictIf(!BandFund.canDisable(band), RejectionReason.FUND_NOT_EMPTY) { "The band fund still holds or owes money" }
                band.copy(treasurerMemberId = null)
            }
            is TransferOwnership -> transferOwnership(band, op)
            is InviteToSettleTogether -> {
                val from = band.member(op.fromMemberId).orNotFound { "Member ${op.fromMemberId}" }
                val to = band.member(op.toMemberId).orNotFound { "Member ${op.toMemberId}" }
                conflictIf(!SettleTogether.mayInvite(band, from, to), RejectionReason.CANNOT_SETTLE_TOGETHER) { "${from.id} cannot invite ${to.id} to settle together" }
                band.updateMember(from.id) { it.copy(invitedToSettle = to.id) }
            }
            is CancelSettleTogether -> band.dropInvite(op.fromMemberId, op.toMemberId)
            is DeclineSettleTogether -> band.dropInvite(op.fromMemberId, op.toMemberId)
            is AcceptSettleTogether -> {
                band.dropInvite(op.fromMemberId, op.toMemberId)
                    .updateMember(op.fromMemberId) { it.copy(settlesWith = op.toMemberId) }
                    .updateMember(op.toMemberId) { it.copy(settlesWith = op.fromMemberId) }
                    // Both still may settle together (§7.2).
                    .also { conflictIf(SettleTogether.tidy(it) != it, RejectionReason.CANNOT_SETTLE_TOGETHER) { "They cannot settle together" } }
            }
            is StopSettlingTogether -> {
                conflictIf(band.member(op.memberId)?.settlesWith != op.partnerId, RejectionReason.NOT_FOUND) { "${op.memberId} does not settle with ${op.partnerId}" }
                SettleTogether.keepSettled(band, op.memberId, op.partnerId)
                    .updateMember(op.memberId) { it.copy(settlesWith = null) }.updateMember(op.partnerId) { it.copy(settlesWith = null) }
            }

            is CreateEvent -> createEvent(band, op, ctx)
            is UpdateEvent -> band.updateEvent(op.eventId) { event ->
                val location = if (op.location != null) op.location.value.blankToNull() else event.location
                event.copy(
                    name = op.name?.value?.validName("Event name") ?: event.name,
                    date = op.date?.value ?: event.date,
                    location = location,
                    // Clearing the location also drops its Maps link (old apps don't know the link exists).
                    mapUrl = when {
                        op.mapUrl != null -> validMapUrl(op.mapUrl.value)
                        location == null -> null
                        else -> event.mapUrl
                    },
                    notes = if (op.notes != null) op.notes.value.blankToNull() else event.notes,
                )
            }
            is SetEventArchived -> band.updateEvent(op.eventId) { event ->
                conflictIf(op.archived && !event.archived && !SettlementCalculator.settle(band, event).canArchive, RejectionReason.EVENT_UNSETTLED) {
                    "Event ${event.id} is not settled"
                }
                event.copy(archived = op.archived)
            }
            is SetEventAgenda -> band.updateEvent(op.eventId) { it.copy(agenda = validAgenda(op.agenda)) }
            is SetAgendaTemplate -> {
                invalidIf(op.items.size > AgendaTime.MAX_TIMES, RejectionReason.INVALID_INPUT) { "At most ${AgendaTime.MAX_TIMES} agenda times" }
                op.items.forEach { validAgendaLabel(it.kind, it.label) }
                val items = op.items.map { it.copy(label = it.label?.trim()) }
                // Exactly the built-in times is stored as "the defaults", so built-in times added later apply too.
                band.copy(agendaTemplate = items.takeUnless { it == AgendaKind.entries.map { kind -> AgendaTemplateItem(kind) } })
            }
            is DeleteEvent -> deleteEvent(band, op)
            is AddParticipant -> addParticipant(band, op, ctx)
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
            is SetFixedShare -> band.updateEvent(op.eventId) { event ->
                event.participant(op.partyId).orNotFound { "Participant ${op.partyId}" }
                op.amount?.let { validatePositive(it, "Fixed share") }
                op.weight?.let { validateWeight(it) }
                event.copy(
                    participants = event.participants.map {
                        if (it.partyId == op.partyId) it.copy(fixedShare = op.amount, weight = op.weight ?: it.weight) else it
                    },
                )
            }
            is RemoveParticipant -> removeParticipant(band, op)

            is AddHolding -> band.updateEvent(op.eventId) { event ->
                invalidIf(event.holdings.any { it.id == op.holdingId }, RejectionReason.DUPLICATE_ID) { "Holding ${op.holdingId} already exists" }
                // Or the organiser: a fee agreed but not paid yet (ITERATION_4 §2.2).
                invalidIf(op.partyId != ORGANISER && event.participant(op.partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "Only participants can hold event money" }
                invalidIf(Artists.isArtist(band, op.partyId), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not hold event money" }
                validatePositive(op.amount, "Holding amount")
                event.copy(holdings = event.holdings + Holding(op.holdingId, op.partyId, op.amount, op.source, op.note.blankToNull(), recordedBy = ctx.memberId))
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
            is ReceiveAwaited -> band.updateEvent(op.eventId) { event ->
                val awaited = event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
                invalidIf(!awaited.awaited, RejectionReason.INVALID_INPUT) { "Only an awaited fee can be received" }
                invalidIf(event.holdings.any { it.id == op.receivedId }, RejectionReason.DUPLICATE_ID) { "Holding ${op.receivedId} already exists" }
                validatePositive(op.amount, "Received amount")
                invalidIf(op.amount > awaited.amount, RejectionReason.INVALID_AMOUNT) { "More than is awaited" }
                if (op.partyId == BAND_FUND) {
                    invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "The band fund is off" }
                } else {
                    invalidIf(event.participant(op.partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "Only participants can hold event money" }
                    invalidIf(Artists.isArtist(band, op.partyId), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not hold event money" }
                }
                val full = op.amount == awaited.amount
                // Its files (the contract) go with the money once all of it is in.
                val received = Holding(op.receivedId, op.partyId, op.amount, awaited.source, awaited.note, if (full) awaited.attachments else emptyList(), ctx.memberId)
                val rest = if (full) emptyList() else listOf(awaited.copy(amount = awaited.amount - op.amount))
                event.copy(holdings = event.holdings.flatMap { if (it.id == awaited.id) rest + received else listOf(it) })
            }
            is RemoveHolding -> band.updateEvent(op.eventId) { event ->
                event.holdings.firstOrNull { it.id == op.holdingId }.orNotFound { "Holding ${op.holdingId}" }
                event.copy(holdings = event.holdings.filterNot { it.id == op.holdingId })
            }

            is SetRefund -> setRefund(band, op)
            is SetFundAmount -> band.updateEvent(op.eventId) { event ->
                invalidIf(!op.amount.isZero && band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "Choose a treasurer before using the band fund" }
                event.copy(fundAmount = op.amount)
            }
            is AddFundEntry -> addFundEntry(band, op)
            is UpdateFundEntry -> updateFundEntry(band, op)
            is RemoveFundEntry -> {
                band.fundEntries.firstOrNull { it.id == op.entryId }.orNotFound { "Fund entry ${op.entryId}" }
                band.copy(fundEntries = band.fundEntries.filterNot { it.id == op.entryId })
            }

            is RecordTransfer -> if (op.together) recordTogether(band, op, ctx) else band.updateEvent(op.eventId) { event ->
                invalidIf(event.transfers.any { it.id == op.transferId }, RejectionReason.DUPLICATE_ID) { "Transfer ${op.transferId} already exists" }
                validateTransferParty(band, event, op.from)
                validateTransferParty(band, event, op.to)
                invalidIf(op.from == op.to, RejectionReason.SAME_PARTY) { "Cannot transfer to the same party" }
                validatePositive(op.amount, "Transfer amount")
                // An artist is paid what they are owed, never more: they don't hold money (ITERATION_4 §1.1).
                if (Artists.isArtist(band, op.to)) {
                    val owed = SettlementCalculator.settle(band, event).line(op.to)?.delta?.let { -it } ?: Money.ZERO
                    invalidIf(op.amount > owed, RejectionReason.ARTIST_HOLDS_NO_MONEY) { "More than the artist is owed" }
                }
                event.copy(transfers = event.transfers + Transfer(op.transferId, op.from, op.to, op.amount, op.note.blankToNull(), ctx.timestamp, ctx.memberId))
            }
            is SettleSuggestions -> settleSuggestions(band, op, ctx)
            is RemoveTransfer -> band.updateEvent(op.eventId) { event ->
                event.transfers.firstOrNull { it.id == op.transferId }.orNotFound { "Transfer ${op.transferId}" }
                event.copy(transfers = event.transfers.filterNot { it.id == op.transferId })
            }

            is AddCost -> addCost(band, op, ctx)
            is UpdateCost -> updateCost(band, op, ctx)
            is DeleteCost -> deleteCost(band, op, ctx)
            is RefuseCost -> {
                val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
                conflictIf(CostLedger.refunded(band, cost.id).isPositive, RejectionReason.COST_PARTLY_REFUNDED) { "Part of the cost is already refunded" }
                band.updateCost(cost.id) { it.copy(refused = true) }
            }
            is AddAttachment -> band.updateAttachments(op.owner) { attachments ->
                val max = if (op.owner is AttachmentOwner.OfEvent) MAX_EVENT_ATTACHMENTS else MAX_ATTACHMENTS
                invalidIf(attachments.size >= max, RejectionReason.TOO_MANY_ATTACHMENTS) { "At most $max attachments here" }
                // Unique in the whole band: the id names its file in storage (ITERATION_3 R6).
                invalidIf(band.allAttachments().any { it.id == op.attachment.id }, RejectionReason.DUPLICATE_ID) { "Attachment ${op.attachment.id} already exists" }
                // The size is signed into the upload URL, so the stored file cannot be larger than declared here.
                val size = op.attachment.sizeBytes
                invalidIf(size == null || size <= 0, RejectionReason.INVALID_INPUT) { "Attachment size is required" }
                invalidIf(size!! > op.attachment.kind.maxBytes, RejectionReason.ATTACHMENT_TOO_LARGE) { "Attachment exceeds ${op.attachment.kind.maxBytes} bytes" }
                invalidIf(!op.attachment.id.matches(Regex("[A-Za-z0-9_-]{1,64}")), RejectionReason.INVALID_INPUT) { "Invalid attachment id" }
                // A device's file name can be anything: kept short rather than refused.
                attachments + op.attachment.copy(fileName = op.attachment.fileName?.trim()?.take(Attachment.MAX_FILE_NAME)?.ifEmpty { null })
            }
            is RemoveAttachment -> band.updateAttachments(op.owner) { attachments ->
                attachments.firstOrNull { it.id == op.attachmentId }.orNotFound { "Attachment ${op.attachmentId}" }
                attachments.filterNot { it.id == op.attachmentId }
            }
            is RenameAttachment -> band.updateAttachments(op.owner) { attachments ->
                attachments.firstOrNull { it.id == op.attachmentId }.orNotFound { "Attachment ${op.attachmentId}" }
                val name = validFileName(op.fileName)
                attachments.map { if (it.id == op.attachmentId) it.copy(fileName = name) else it }
            }
            is Undo -> Undoing.apply(band, op)
        }
        // While the fund is off (ITERATION_3 R10), a change may not leave it holding or owing money: that needs a treasurer.
        conflictIf(
            result.treasurerMemberId == null && band.treasurerMemberId == null && BandFund.canDisable(band) && !BandFund.canDisable(result),
            RejectionReason.NO_TREASURER,
        ) { "Choose a treasurer before using the band fund" }
        // Archiving, unlinking or a role change can end a pair or an invitation (ITERATION_5 §7); an undo is checked instead.
        val tidied = if (op is Undo) result else SettleTogether.tidy(result, band)
        return op.eventId
            ?.takeIf { op !is DeleteEvent }
            ?.let { eventId -> tidied.updateEvent(eventId) { it.copy(updatedAt = ctx.timestamp) } }
            ?: tidied
    }

    /** An invitation to settle together, answered or taken back. */
    private fun Band.dropInvite(fromMemberId: Id, toMemberId: Id): Band {
        conflictIf(member(fromMemberId)?.invitedToSettle != toMemberId, RejectionReason.NOT_FOUND) { "No invitation from $fromMemberId to $toMemberId" }
        return updateMember(fromMemberId) { it.copy(invitedToSettle = null) }
    }

    // ---- roster ----

    private fun addMember(band: Band, op: AddMember): Band {
        invalidIf(band.member(op.memberId) != null, RejectionReason.DUPLICATE_ID) { "Member ${op.memberId} already exists" }
        return band.copy(members = band.members + Member(op.memberId, op.name.validName("Member name")))
    }

    private fun moveMember(band: Band, op: MoveMember): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.toIndex !in band.members.indices, RejectionReason.INVALID_INPUT) { "Index ${op.toIndex} out of range" }
        val others = band.members.filterNot { it.id == op.memberId }
        return band.copy(members = others.take(op.toIndex) + member + others.drop(op.toIndex))
    }

    private fun setMemberArchived(band: Band, op: SetMemberArchived): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.archived && member.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "The owner cannot be archived" }
        invalidIf(op.archived && band.treasurerMemberId == op.memberId, RejectionReason.TREASURER_MUST_STAY) { "Choose another treasurer first" }
        // A removed member's photo leaves the band with them (the next sign-in brings it back if they return).
        return band.updateMember(op.memberId) { it.copy(archived = op.archived, pictureUrl = it.pictureUrl.takeUnless { op.archived }) }
    }

    private fun deleteMember(band: Band, op: DeleteMember): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        conflictIf(member.userId != null, RejectionReason.MEMBER_LINKED) { "Unlink the user before deleting the member" }
        conflictIf(band.treasurerMemberId == member.id, RejectionReason.TREASURER_MUST_STAY) { "Choose another treasurer first" }
        conflictIf(band.costs.any { it.memberId == member.id }, RejectionReason.MEMBER_HAS_HISTORY) { "Member has costs; archive instead" }
        conflictIf(band.events.any { it.participant(member.id) != null }, RejectionReason.MEMBER_HAS_HISTORY) { "Member took part in events; archive instead" }
        return band.copy(members = band.members.filterNot { it.id == member.id })
    }

    private fun setMemberShare(band: Band, op: SetMemberShare, ctx: OpContext): Band {
        validateActiveMember(band, op.memberId)
        validateWeight(op.weight)
        op.fixedShare?.let { validatePositive(it, "Fixed share") }
        val member = band.member(op.memberId)!!
        var result = band.updateMember(op.memberId) { it.copy(shareWeight = op.weight, shareFixed = op.fixedShare) }
        // Events that changed since the user chose them (archived, settled, the member removed, e.g. on another phone)
        // are left alone rather than losing the new default with them.
        for (eventId in op.events.distinct()) {
            band.event(eventId)?.takeIf { MemberShares.canApply(band, it, member) } ?: continue
            result = result.updateEvent(eventId) {
                it.copy(
                    participants = it.participants.map { p ->
                        if (p.partyId != op.memberId) p
                        // A fixed amount keeps the percentage underneath, so switching back restores it (as in events).
                        else if (op.fixedShare != null) p.copy(fixedShare = op.fixedShare)
                        else p.copy(weight = op.weight, fixedShare = null)
                    },
                    updatedAt = ctx.timestamp,
                )
            }
        }
        return result
    }

    private fun setMemberRole(band: Band, op: SetMemberRole): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "Use ownership transfer to make someone the owner" }
        invalidIf(member.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "The owner's role cannot be changed" }
        if (op.role == Role.ARTIST && member.role != Role.ARTIST) validateHoldsNoMoney(band, member.id)
        if (op.role == Role.VIEWER && member.role != Role.VIEWER) validateCanWatchOnly(band, member.id)
        return band.updateMember(op.memberId) { it.copy(role = op.role) }
    }

    private fun linkUser(band: Band, op: LinkUser): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        conflictIf(member.userId != null, RejectionReason.MEMBER_LINKED) { "Member is already linked to a user" }
        conflictIf(band.memberOfUser(op.userId) != null, RejectionReason.USER_ALREADY_MEMBER) { "User is already a member of this band" }
        invalidIf(op.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "Invites cannot grant ownership" }
        if (op.role == Role.ARTIST) validateHoldsNoMoney(band, member.id)
        if (op.role == Role.VIEWER) validateCanWatchOnly(band, member.id)
        return band.updateMember(op.memberId) { it.copy(userId = op.userId, role = op.role, archived = false) }
    }

    private fun unlinkUser(band: Band, op: UnlinkUser): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(member.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "Transfer ownership before unlinking the owner" }
        // The photo and the BLIK number were the user's: they go with the account.
        return band.updateMember(op.memberId) { it.copy(userId = null, role = Role.MEMBER, pictureUrl = null, blikPhone = null) }
    }

    private fun setTreasurer(band: Band, op: SetTreasurer): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(member.archived, RejectionReason.ARCHIVED_MEMBER) { "An archived member cannot hold the band fund" }
        invalidIf(Artists.isArtist(band, member.id), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "An artist cannot hold the band fund" }
        // The holder records the fund's transfers, which a viewer may not (V5-M1 B2).
        invalidIf(member.role == Role.VIEWER, RejectionReason.VIEWER_NOT_PARTICIPANT) { "A viewer cannot hold the band fund" }
        return band.copy(treasurerMemberId = member.id)
    }

    private fun transferOwnership(band: Band, op: TransferOwnership): Band {
        val target = band.member(op.toMemberId).orNotFound { "Member ${op.toMemberId}" }
        invalidIf(target.userId == null || target.role != Role.ADMIN, RejectionReason.OWNERSHIP_NEEDS_ADMIN) { "Ownership can only go to a linked admin" }
        // An archived owner could do nothing at all, the band included (V5-M1 B3a).
        invalidIf(target.archived, RejectionReason.ARCHIVED_MEMBER) { "Ownership cannot go to an archived member" }
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

    private fun createEvent(band: Band, op: CreateEvent, ctx: OpContext): Band {
        invalidIf(band.event(op.eventId) != null, RejectionReason.DUPLICATE_ID) { "Event ${op.eventId} already exists" }
        val guestIds = op.guests.map { it.id }.toSet()
        invalidIf(guestIds.size != op.guests.size, RejectionReason.DUPLICATE_ID) { "Duplicate guest ids" }
        invalidIf(guestIds.any { band.member(it) != null }, RejectionReason.DUPLICATE_ID) { "Guest id already used by a member" }
        invalidIf(op.participants.map { it.partyId }.toSet().size != op.participants.size, RejectionReason.INVALID_INPUT) { "Duplicate participants" }
        op.participants.forEach { participant ->
            validateWeight(participant.weight)
            participant.fixedShare?.let { validatePositive(it, "Fixed share") }
            if (participant.partyId !in guestIds) {
                validateActiveMember(band, participant.partyId)
                validateNotViewer(band, participant.partyId)
            }
        }
        val event = Event(
            id = op.eventId,
            name = op.name.validName("Event name"),
            date = op.date,
            location = op.location.blankToNull(),
            mapUrl = validMapUrl(op.mapUrl),
            notes = op.notes.blankToNull(),
            guests = op.guests.map { it.copy(name = it.name.validName("Guest name")) },
            // Who added each participant (the creator), for a personal reminder prompt (ITERATION_2 §5.2).
            participants = op.participants.map { it.copy(addedBy = ctx.memberId) },
            agenda = validAgenda(op.agenda),
        )
        return band.copy(events = band.events + event)
    }

    private fun deleteEvent(band: Band, op: DeleteEvent): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        conflictIf(event.transfers.isNotEmpty(), RejectionReason.EVENT_HAS_TRANSFERS) { "Event has recorded transfers; archive it instead" }
        return band.copy(
            events = band.events.filterNot { it.id == event.id },
            costs = band.costs.map { if (it.eventId == event.id) it.copy(eventId = null) else it },
        )
    }

    /** A link people open from the app: https only, and bounded. */
    private fun validMapUrl(url: String?): String? {
        val value = url?.trim()?.ifEmpty { null } ?: return null
        invalidIf(!Places.isValidUrl(value), RejectionReason.INVALID_INPUT) { "Invalid map link" }
        return value
    }

    private fun validAgenda(agenda: List<AgendaTime>): List<AgendaTime> {
        invalidIf(agenda.size > AgendaTime.MAX_TIMES, RejectionReason.INVALID_INPUT) { "At most ${AgendaTime.MAX_TIMES} agenda times" }
        invalidIf(agenda.map { it.id }.toSet().size != agenda.size, RejectionReason.DUPLICATE_ID) { "Duplicate agenda ids" }
        invalidIf(agenda.any { it.id.isBlank() || it.id.length > AgendaTime.MAX_ID }, RejectionReason.INVALID_INPUT) { "Invalid agenda id" }
        agenda.forEach { validAgendaLabel(it.kind, it.label) }
        return agenda.map { it.copy(label = it.label?.trim()) }
    }

    /** A built-in kind needs no label; a custom entry needs one. Any label is short. */
    private fun validAgendaLabel(kind: AgendaKind?, label: String?) {
        invalidIf(kind == null && label.isNullOrBlank(), RejectionReason.EMPTY_NAME) { "Agenda label is required" }
        invalidIf((label?.trim()?.length ?: 0) > AgendaTime.MAX_LABEL, RejectionReason.INVALID_INPUT) { "Agenda label is too long" }
    }

    private fun addParticipant(band: Band, op: AddParticipant, ctx: OpContext): Band {
        validateActiveMember(band, op.memberId)
        validateNotViewer(band, op.memberId)
        validateWeight(op.weight)
        op.fixedShare?.let { validatePositive(it, "Fixed share") }
        val updated = band.updateEvent(op.eventId) { event ->
            invalidIf(event.participant(op.memberId) != null, RejectionReason.ALREADY_PARTICIPANT) { "Member already takes part" }
            event.copy(participants = event.participants + Participant(op.memberId, op.weight, addedBy = ctx.memberId, fixedShare = op.fixedShare))
        }
        return updated
    }

    private fun addGuest(band: Band, op: AddGuest): Band = band.updateEvent(op.eventId) { event ->
        invalidIf(event.guest(op.guestId) != null || band.member(op.guestId) != null, RejectionReason.DUPLICATE_ID) { "Id ${op.guestId} already in use" }
        validateWeight(op.weight)
        op.fixedShare?.let { validatePositive(it, "Fixed share") }
        event.copy(
            guests = event.guests + Guest(op.guestId, op.name.validName("Guest name")),
            participants = event.participants + Participant(op.guestId, op.weight, fixedShare = op.fixedShare),
        )
    }

    private fun promoteGuest(band: Band, op: PromoteGuest): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        val guest = event.guest(op.guestId).orNotFound { "Guest ${op.guestId}" }
        invalidIf(band.member(op.memberId) != null, RejectionReason.DUPLICATE_ID) { "Member ${op.memberId} already exists" }
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
        conflictIf(event.holdings.any { it.partyId == op.partyId }, RejectionReason.PARTICIPANT_HAS_MONEY) { "Participant holds event money; remove it (and its transfers) first" }
        conflictIf(event.transfers.any { it.from == op.partyId || it.to == op.partyId }, RejectionReason.PARTICIPANT_HAS_TRANSFERS) { "Participant has recorded transfers" }
        conflictIf(event.refunds.any { band.cost(it.costId)?.memberId == op.partyId }, RejectionReason.PARTICIPANT_HAS_REFUNDS) { "Participant has refunds here; defer them first" }
        event.copy(
            participants = event.participants.filterNot { it.partyId == op.partyId },
            guests = event.guests.filterNot { it.id == op.partyId },
        )
    }

    // ---- refunds, fund, transfers ----

    private fun setRefund(band: Band, op: SetRefund): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        return band.updateEvent(op.eventId) { event ->
            invalidIf(op.amount.isNegative, RejectionReason.INVALID_AMOUNT) { "Refund cannot be negative" }
            if (op.amount.isZero) return@updateEvent event.copy(refunds = event.refunds.filterNot { it.costId == cost.id })
            invalidIf(!CostLedger.isRefundableAt(cost, event), RejectionReason.NOT_REFUNDABLE_HERE) { "Costs can only be refunded at events their owner takes part in" }
            conflictIf(cost.refused, RejectionReason.COST_REFUSED) { "The cost was refused" }
            val max = CostLedger.maxRefundAt(band, cost, event)
            invalidIf(op.amount > max, RejectionReason.REFUND_TOO_LARGE) { "Refund ${op.amount} exceeds outstanding $max" }
            val refunds = if (event.refundOf(cost.id) != null) {
                event.refunds.map { if (it.costId == cost.id) it.copy(amount = op.amount) else it }
            } else {
                event.refunds + Refund(cost.id, op.amount)
            }
            event.copy(refunds = refunds)
        }
    }

    private fun addFundEntry(band: Band, op: AddFundEntry): Band {
        invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "Choose a treasurer before using the band fund" }
        invalidIf(band.fundEntries.any { it.id == op.entryId }, RejectionReason.DUPLICATE_ID) { "Fund entry ${op.entryId} already exists" }
        invalidIf(op.amount.isZero, RejectionReason.INVALID_AMOUNT) { "Fund entry amount cannot be zero" }
        op.costId?.let { costId ->
            val cost = band.cost(costId).orNotFound { "Cost $costId" }
            invalidIf(!op.amount.isNegative, RejectionReason.INVALID_AMOUNT) { "A cost refund from the fund must be negative" }
            conflictIf(cost.refused, RejectionReason.COST_REFUSED) { "The cost was refused" }
            invalidIf(-op.amount > CostLedger.outstanding(band, cost), RejectionReason.REFUND_TOO_LARGE) { "Refund exceeds the outstanding amount" }
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
        invalidIf(updated.amount.isZero, RejectionReason.INVALID_AMOUNT) { "Fund entry amount cannot be zero" }
        updated.costId?.let { costId ->
            val cost = band.cost(costId).orNotFound { "Cost $costId" }
            invalidIf(!updated.amount.isNegative, RejectionReason.INVALID_AMOUNT) { "A cost refund from the fund must be negative" }
            invalidIf(-updated.amount > CostLedger.outstanding(band, cost) - entry.amount, RejectionReason.REFUND_TOO_LARGE) { "Refund exceeds the outstanding amount" }
        }
        return band.copy(fundEntries = band.fundEntries.map { if (it.id == entry.id) updated else it })
    }

    private fun settleSuggestions(band: Band, op: SettleSuggestions, ctx: OpContext): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        val settlement = SettlementCalculator.settle(band, event)
        conflictIf(!settlement.canSettle, RejectionReason.CANNOT_SETTLE) { "Event cannot be settled (status ${settlement.status}, problems ${settlement.problems})" }
        val suggestions = op.partyId?.let { settlement.suggestionsFor(it) } ?: settlement.suggestions
        // A pair's transfer is recorded between real people, maybe as two (ITERATION_5 §7), each split against what the
        // ones before it left: who holds what, who is still owed.
        var result = band
        suggestions.forEachIndexed { index, suggestion ->
            val now = result.event(event.id)!!
            val parts = SettleTogether.split(result, SettlementCalculator.settle(result, now), suggestion.from, suggestion.to, suggestion.amount)
            val transfers = parts.mapIndexed { part, it ->
                Transfer("${ctx.opId}-$index" + if (part > 0) "-$part" else "", it.from, it.to, it.amount, null, ctx.timestamp, ctx.memberId)
            }
            // Ids made from the op's id: an op id seen before (a resent op) must not make two transfers one id (V5-M1 B3c).
            invalidIf(transfers.any { t -> now.transfers.any { it.id == t.id } }, RejectionReason.DUPLICATE_ID) { "Transfer ids already exist" }
            validateArtistsPaid(result, now, parts)
            result = result.updateEvent(event.id) { it.copy(transfers = it.transfers + transfers) }
        }
        return result
    }

    /** A suggestion's transfer, settled or in part, where a pair is either partner (ITERATION_5 §7). */
    private fun recordTogether(band: Band, op: RecordTransfer, ctx: OpContext): Band {
        val event = band.event(op.eventId).orNotFound { "Event ${op.eventId}" }
        validateTransferParty(band, event, op.from)
        validateTransferParty(band, event, op.to)
        validatePositive(op.amount, "Transfer amount")
        val settlement = SettlementCalculator.settle(band, event)
        invalidIf(op.from == op.to || settlement.party(op.from)?.let { op.to in it.partyIds } == true, RejectionReason.SAME_PARTY) { "Cannot transfer to the same party" }
        val parts = SettleTogether.split(band, settlement, op.from, op.to, op.amount)
        val transfers = parts.mapIndexed { index, part ->
            Transfer(if (index == 0) op.transferId else "${op.transferId}-$index", part.from, part.to, part.amount, op.note.blankToNull(), ctx.timestamp, ctx.memberId)
        }
        invalidIf(transfers.any { t -> event.transfers.any { it.id == t.id } }, RejectionReason.DUPLICATE_ID) { "Transfer ${op.transferId} already exists" }
        validateArtistsPaid(band, event, parts)
        return band.updateEvent(event.id) { it.copy(transfers = it.transfers + transfers) }
    }

    /** An artist is paid what they are owed, never more, and pays nothing (ITERATION_4 §1.1); the split sees to both. */
    private fun validateArtistsPaid(band: Band, event: Event, parts: List<com.jacekgajek.kapelusz.domain.SuggestedTransfer>) {
        val settlement = SettlementCalculator.settle(band, event)
        parts.forEach { part ->
            invalidIf(Artists.isArtist(band, part.from), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "Artists do not pass money on" }
            if (Artists.isArtist(band, part.to)) {
                val owed = settlement.line(part.to)?.delta?.let { -it } ?: Money.ZERO
                invalidIf(part.amount > owed, RejectionReason.ARTIST_HOLDS_NO_MONEY) { "More than the artist is owed" }
            }
        }
    }

    private fun validateTransferParty(band: Band, event: Event, partyId: Id) {
        if (partyId == BAND_FUND) {
            invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "Choose a treasurer before using the band fund" }
        } else {
            invalidIf(event.participant(partyId) == null, RejectionReason.NOT_A_PARTICIPANT) { "$partyId does not take part in the event" }
        }
    }

    // ---- costs ----

    private fun addCost(band: Band, op: AddCost, ctx: OpContext): Band {
        invalidIf(band.cost(op.costId) != null, RejectionReason.DUPLICATE_ID) { "Cost ${op.costId} already exists" }
        validateActiveMember(band, op.memberId)
        validatePositive(op.amount, "Cost amount")
        op.incurredForEventId?.let { validateCostEvent(band, it, op.memberId) }
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
        return op.incurredForEventId?.let { includeByLead(added, it, cost.id, ctx) } ?: added
    }

    private fun updateCost(band: Band, op: UpdateCost, ctx: OpContext): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        op.incurredForEventId?.value?.takeIf { it != cost.eventId }?.let { validateCostEvent(band, it, cost.memberId) }
        val newAmount = op.amount?.value ?: cost.amount
        validatePositive(newAmount, "Cost amount")
        var result = band.updateCost(cost.id) {
            it.copy(
                amount = newAmount,
                date = op.date?.value ?: it.date,
                eventId = if (op.incurredForEventId != null) op.incurredForEventId.value else it.eventId,
                category = if (op.category != null) op.category.value else it.category,
                description = if (op.description != null) op.description.value.blankToNull() else it.description,
                // Its owner resubmits a refused cost by editing it (ITERATION_4 §1.2); nobody else can.
                refused = it.refused && !(op.resubmit && ctx.memberId == it.memberId),
            )
        }
        // A refund that covered the whole remainder at the cost's own event keeps covering it (not when the cost moves
        // to another event in the same change: its refund there is what counts then).
        val moves = op.incurredForEventId != null && op.incurredForEventId.value != cost.eventId
        val ownEvent = cost.eventId?.takeUnless { moves }?.let { band.event(it) }
        val ownRefund = ownEvent?.refundOf(cost.id)
        // Only while the event is open (ITERATION_3 R2): once money moved or it is archived, refunds are a lead's decision.
        if (ownEvent != null && ownRefund != null && newAmount != cost.amount && !ownEvent.archived && ownEvent.transfers.isEmpty()) {
            val refundedElsewhere = CostLedger.refunded(band, cost.id) - ownRefund.amount
            if (ownRefund.amount == cost.amount - refundedElsewhere) {
                // Only a lead's edit raises the refund; anyone else's raise waits for a lead (ITERATION_4 §1.2).
                val adjusted = (newAmount - refundedElsewhere).let { if (isLead(band, ctx) || it < ownRefund.amount) it else ownRefund.amount }
                invalidIf(adjusted.isNegative, RejectionReason.COST_BELOW_REFUNDED) { "Cost amount is below what was already refunded" }
                result = result.updateEvent(ownEvent.id) { event ->
                    event.copy(
                        refunds = event.refunds.map { if (it.costId == cost.id) it.copy(amount = adjusted) else it },
                        updatedAt = ctx.timestamp,
                    )
                }
            }
        }
        invalidIf(newAmount < CostLedger.refunded(result, cost.id), RejectionReason.COST_BELOW_REFUNDED) { "Cost amount is below what was already refunded" }
        // Moving the cost to another event pre-includes it there, as when it is added.
        val newEventId = op.incurredForEventId?.value
        if (newEventId != null && newEventId != cost.eventId) result = includeByLead(result, newEventId, cost.id, ctx)
        return result
    }

    private fun deleteCost(band: Band, op: DeleteCost, ctx: OpContext): Band {
        val cost = band.cost(op.costId).orNotFound { "Cost ${op.costId}" }
        conflictIf(band.fundEntries.any { it.costId == cost.id }, RejectionReason.COST_REFUNDED_FROM_FUND) { "Cost was refunded from the band fund" }
        // Its refund in an archived event would change that event (ITERATION_3 R10).
        conflictIf(band.events.any { it.archived && it.refundOf(cost.id) != null }, RejectionReason.ARCHIVED_EVENT) { "Cost is refunded by an archived event" }
        return band.copy(
            costs = band.costs.filterNot { it.id == cost.id },
            events = band.events.map { event ->
                if (event.refundOf(cost.id) == null) {
                    event
                } else {
                    event.copy(refunds = event.refunds.filterNot { it.costId == cost.id }, updatedAt = ctx.timestamp)
                }
            },
        )
    }

    /** A cost can be incurred only for an event its owner takes part in (ITERATION_3 R2): only there it is refunded. */
    private fun validateCostEvent(band: Band, eventId: Id, memberId: Id) {
        val event = band.event(eventId).orNotFound { "Event $eventId" }
        invalidIf(event.participant(memberId) == null, RejectionReason.NOT_A_PARTICIPANT) { "The cost's owner does not take part in the event" }
        conflictIf(event.archived, RejectionReason.ARCHIVED_EVENT) { "Event $eventId is archived" }
    }

    /**
     * A cost a lead enters (theirs or anyone's) for an event is included there in full, while the event is still open
     * (ITERATION_3 R2): once money moved (transfers recorded) or it is archived, refunds are decided one by one. Costs
     * others enter wait for a lead's Include or Refuse (ITERATION_4 §1.2).
     */
    private fun includeByLead(band: Band, eventId: Id, costId: Id, ctx: OpContext): Band {
        if (!isLead(band, ctx)) return band
        val event = band.event(eventId) ?: return band
        val cost = band.cost(costId) ?: return band
        if (cost.refused || event.participant(cost.memberId) == null || event.archived || event.transfers.isNotEmpty() || event.refundOf(cost.id) != null) return band
        val amount = CostLedger.outstanding(band, cost).takeIf { it.isPositive } ?: return band
        return band.updateEvent(eventId) { it.copy(refunds = it.refunds + Refund(cost.id, amount), updatedAt = ctx.timestamp) }
    }

    /** A file name people see and download under: not empty; a long one is shortened, as when a file is added. */
    private fun validFileName(name: String): String = name.trim().take(Attachment.MAX_FILE_NAME).also {
        invalidIf(it.isEmpty(), RejectionReason.EMPTY_NAME) { "File name cannot be empty" }
    }

    /** The attachments of a cost, a collected entry or an event (ITERATION_4 §2.1), changed by [transform]. */
    private inline fun Band.updateAttachments(owner: AttachmentOwner, transform: (List<Attachment>) -> List<Attachment>): Band = when (owner) {
        is AttachmentOwner.OfCost -> updateCost(owner.costId) { it.copy(attachments = transform(it.attachments)) }
        is AttachmentOwner.OfEvent -> updateEvent(owner.eventId) { it.copy(attachments = transform(it.attachments)) }
        is AttachmentOwner.OfHolding -> updateEvent(owner.eventId) { event ->
            val holding = event.holdings.firstOrNull { it.id == owner.holdingId }.orNotFound { "Holding ${owner.holdingId}" }
            val updated = holding.copy(attachments = transform(holding.attachments))
            event.copy(holdings = event.holdings.map { if (it.id == holding.id) updated else it })
        }
    }

    /**
     * Someone becoming a viewer may not still play in an unsettled event or hold the band fund (V5-M1 B2): a viewer
     * records no transfers, so money they hold would be stuck.
     */
    private fun validateCanWatchOnly(band: Band, memberId: Id) =
        conflictIf(!Viewers.mayBecome(band, memberId), RejectionReason.VIEWER_STILL_PLAYS) { "Settle their events or hand over the band fund first" }

    /** A viewer only watches: they don't play in events (and so never get a share or a transfer). */
    private fun validateNotViewer(band: Band, memberId: Id) =
        invalidIf(band.member(memberId)?.role == Role.VIEWER, RejectionReason.VIEWER_NOT_PARTICIPANT) { "Viewers take no part in events" }

    /** The change is a lead's (or above), or the server's own, which is allowed everything. */
    private fun isLead(band: Band, ctx: OpContext) = ctx.memberId == null || band.member(ctx.memberId)?.role?.atLeast(Role.LEAD) == true

    /** Someone becoming an artist (ITERATION_4 §1.1) may not hold money in an unsettled event or the band fund. */
    private fun validateHoldsNoMoney(band: Band, memberId: Id) =
        conflictIf(Artists.holdsMoney(band, memberId), RejectionReason.HOLDS_MONEY) { "Settle their events or hand over the band fund first" }

    // ---- helpers ----

    private fun validateActiveMember(band: Band, memberId: Id) {
        val member = band.member(memberId).orNotFound { "Member $memberId" }
        invalidIf(member.archived, RejectionReason.ARCHIVED_MEMBER) { "Member ${member.name} is archived" }
    }

    private fun validateWeight(weight: Int) = invalidIf(weight !in 0..10_000, RejectionReason.INVALID_SHARE) { "Share weight must be between 0 and 10000" }

    private fun validatePositive(amount: Money, what: String) = invalidIf(!amount.isPositive, RejectionReason.INVALID_AMOUNT) { "$what must be positive" }

    private fun String.validName(what: String): String = trim().also { invalidIf(it.isEmpty(), RejectionReason.EMPTY_NAME) { "$what cannot be empty" } }

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

