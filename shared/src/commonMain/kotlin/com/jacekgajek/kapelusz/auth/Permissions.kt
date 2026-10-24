package com.jacekgajek.kapelusz.auth

import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.MoveMember
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.PromoteGuest
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RefuseCost
import com.jacekgajek.kapelusz.ops.Rejection
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.RenameAttachment
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.RenameGuest
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.ops.UpdateHolding

/** Who performs an operation. */
sealed interface Actor {
    /** An app user, identified by Google `sub`. */
    data class User(val userId: String) : Actor

    /** The server itself, e.g. accepting an invite. Allowed everything. */
    data object System : Actor
}

/**
 * The role matrix (ITERATION_1 §3). Runs on clients (to hide actions) and on the server (to enforce them).
 * Data validity is not checked here; that is the reducer's job.
 */
object Permissions {

    fun check(band: Band, actor: Actor, op: Operation): Rejection? {
        if (actor is Actor.System) return null
        val me = band.memberOfUser((actor as Actor.User).userId)?.takeIf { !it.archived }
            ?: return forbidden(RejectionReason.NOT_A_MEMBER, "You are not an active member of this band")
        if (op is LinkUser) return forbidden(RejectionReason.INVITE_ONLY, "Users are linked only by accepting an invite")
        // Settling together is everyone's own decision (ITERATION_5 §7): nobody, admins included, decides it for others.
        settlesFor(op)?.let { if (it != me.id) return forbidden(RejectionReason.ROLE_REQUIRED, "Only for yourself") }

        val required = requiredRole(band, me, op)
        return if (me.role.atLeast(required)) null else forbidden(RejectionReason.ROLE_REQUIRED, "Requires role $required")
    }

    fun isAllowed(band: Band, actor: Actor, op: Operation): Boolean = check(band, actor, op) == null

    /** Lowest role allowed to perform [op] for this actor; "own" exceptions lower it to MEMBER. */
    private fun requiredRole(band: Band, me: Member, op: Operation): Role = when (op) {
        // Undo is allowed to whoever may make the original change (ITERATION_2 §7.2), and the reverse one where that
        // needs more: a member may record their own transfer, but only leads remove transfers.
        is Undo -> maxOf(requiredRole(band, me, op.original), reverseRole(op.original))
        is TransferOwnership -> Role.OWNER

        is RenameBand, is AddMember, is RenameMember, is MoveMember, is SetMemberArchived, is DeleteMember,
        is SetMemberRole, is UnlinkUser, is SetTreasurer, is DisableBandFund, is SetAgendaTemplate, is PromoteGuest, is LinkUser,
        -> Role.ADMIN

        is CreateEvent, is UpdateEvent, is SetEventArchived, is SetEventAgenda, is DeleteEvent, is AddParticipant, is AddGuest,
        is RenameGuest, is SetWeight, is SetFixedShare, is RemoveParticipant, is SetRefund, is SetFundAmount, is AddFundEntry,
        is UpdateFundEntry, is RemoveFundEntry, is RemoveTransfer, is RefuseCost, is ReceiveAwaited,
        is SetMemberShare,
        -> Role.LEAD

        is AddHolding -> ownIf(op.partyId == me.id && band.event(op.eventId)?.participant(me.id) != null)
        is UpdateHolding -> ownIf(band.event(op.eventId)?.holdings?.firstOrNull { it.id == op.holdingId }?.partyId == me.id)
        is RemoveHolding -> ownIf(band.event(op.eventId)?.holdings?.firstOrNull { it.id == op.holdingId }?.partyId == me.id)

        // Either partner settles a pair's transfer (ITERATION_5 §7).
        is RecordTransfer -> ownIf(ownsParty(band, me, op.from) || ownsParty(band, me, op.to) || (op.together && partnerIn(band, me, op.eventId).let { it != null && (it == op.from || it == op.to) }))
        is SettleSuggestions -> ownIf(op.partyId != null && (ownsParty(band, me, op.partyId) || op.partyId == partnerIn(band, me, op.eventId)))

        // Only for oneself (checked above), from members up: artists don't settle together (ITERATION_5 §7.2).
        is InviteToSettleTogether, is CancelSettleTogether, is StopSettlingTogether, is AcceptSettleTogether, is DeclineSettleTogether -> Role.MEMBER

        // Artists too look after their own costs (ITERATION_4 §1.1); money held or moved stays from members up.
        is AddCost -> ownIf(op.memberId == me.id, Role.ARTIST)
        is UpdateCost -> ownCost(band, me, op.costId)
        is DeleteCost -> ownCost(band, me, op.costId)
        is AddAttachment -> ownAttachments(band, me, op.owner)
        is RemoveAttachment -> ownAttachments(band, me, op.owner)
        is RenameAttachment -> ownAttachments(band, me, op.owner)
    }

    /** Whom [me] settles together with in this event: only where both take part (ITERATION_5 §7). */
    private fun partnerIn(band: Band, me: Member, eventId: String): String? =
        band.event(eventId)?.let { SettleTogether.partners(band, it)[me.id] }

    /** The member a settling-together operation is for: the only one who may perform it. */
    private fun settlesFor(op: Operation): String? = when (op) {
        is InviteToSettleTogether -> op.fromMemberId
        is CancelSettleTogether -> op.fromMemberId
        is AcceptSettleTogether -> op.toMemberId
        is DeclineSettleTogether -> op.toMemberId
        is StopSettlingTogether -> op.memberId
        else -> null
    }

    private fun reverseRole(op: Operation): Role = when (op) {
        is RecordTransfer, is SettleSuggestions -> Role.LEAD
        is Undo -> reverseRole(op.original)
        else -> Role.VIEWER
    }

    /** A member acts for themselves; the treasurer also acts for the band fund. */
    private fun ownsParty(band: Band, me: Member, partyId: String) =
        partyId == me.id || (partyId == BAND_FUND && band.treasurerMemberId == me.id)

    private fun ownIf(isOwn: Boolean, own: Role = Role.MEMBER) = if (isOwn) own else Role.LEAD

    /**
     * Files follow what they belong to (ITERATION_4 §2.1): a cost's as the cost, a collected entry's as the entry (its
     * holder, or leads), the event's as the event (leads).
     */
    private fun ownAttachments(band: Band, me: Member, owner: AttachmentOwner): Role = when (owner) {
        is AttachmentOwner.OfCost -> ownCost(band, me, owner.costId)
        is AttachmentOwner.OfHolding -> ownIf(band.event(owner.eventId)?.holdings?.firstOrNull { it.id == owner.holdingId }?.partyId == me.id)
        is AttachmentOwner.OfEvent -> Role.LEAD
    }

    private fun ownCost(band: Band, me: Member, costId: String): Role {
        val cost = band.cost(costId) ?: return Role.LEAD
        return ownIf(cost.memberId == me.id && !CostLedger.isLockedForOwner(band, cost), Role.ARTIST)
    }

    private fun forbidden(reason: RejectionReason, message: String) = Rejection(RejectionCode.FORBIDDEN, message, reason)
}
