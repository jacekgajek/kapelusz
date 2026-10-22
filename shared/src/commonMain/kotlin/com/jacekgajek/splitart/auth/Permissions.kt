package com.jacekgajek.splitart.auth

import com.jacekgajek.splitart.domain.CostLedger
import com.jacekgajek.splitart.model.BAND_FUND
import com.jacekgajek.splitart.model.Band
import com.jacekgajek.splitart.model.Member
import com.jacekgajek.splitart.model.Role
import com.jacekgajek.splitart.ops.AddAttachment
import com.jacekgajek.splitart.ops.Undo
import com.jacekgajek.splitart.ops.AddCost
import com.jacekgajek.splitart.ops.AddFundEntry
import com.jacekgajek.splitart.ops.AddGuest
import com.jacekgajek.splitart.ops.AddHolding
import com.jacekgajek.splitart.ops.AddMember
import com.jacekgajek.splitart.ops.AddParticipant
import com.jacekgajek.splitart.ops.CreateEvent
import com.jacekgajek.splitart.ops.DeleteCost
import com.jacekgajek.splitart.ops.DeleteEvent
import com.jacekgajek.splitart.ops.DeleteMember
import com.jacekgajek.splitart.ops.LinkUser
import com.jacekgajek.splitart.ops.MoveMember
import com.jacekgajek.splitart.ops.Operation
import com.jacekgajek.splitart.ops.PromoteGuest
import com.jacekgajek.splitart.ops.RecordTransfer
import com.jacekgajek.splitart.ops.Rejection
import com.jacekgajek.splitart.ops.RejectionCode
import com.jacekgajek.splitart.ops.RejectionReason
import com.jacekgajek.splitart.ops.RemoveAttachment
import com.jacekgajek.splitart.ops.RemoveFundEntry
import com.jacekgajek.splitart.ops.RemoveHolding
import com.jacekgajek.splitart.ops.RemoveParticipant
import com.jacekgajek.splitart.ops.RemoveTransfer
import com.jacekgajek.splitart.ops.RenameBand
import com.jacekgajek.splitart.ops.RenameGuest
import com.jacekgajek.splitart.ops.RenameMember
import com.jacekgajek.splitart.ops.SetEventArchived
import com.jacekgajek.splitart.ops.SetFundAmount
import com.jacekgajek.splitart.ops.SetMemberArchived
import com.jacekgajek.splitart.ops.SetMemberRole
import com.jacekgajek.splitart.ops.SetRefund
import com.jacekgajek.splitart.ops.SetTreasurer
import com.jacekgajek.splitart.ops.DisableBandFund
import com.jacekgajek.splitart.ops.SetAgendaTemplate
import com.jacekgajek.splitart.ops.SetEventAgenda
import com.jacekgajek.splitart.ops.SetWeight
import com.jacekgajek.splitart.ops.SettleSuggestions
import com.jacekgajek.splitart.ops.TransferOwnership
import com.jacekgajek.splitart.ops.UnlinkUser
import com.jacekgajek.splitart.ops.UpdateCost
import com.jacekgajek.splitart.ops.UpdateEvent
import com.jacekgajek.splitart.ops.UpdateFundEntry
import com.jacekgajek.splitart.ops.UpdateHolding

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
        is RenameGuest, is SetWeight, is RemoveParticipant, is SetRefund, is SetFundAmount, is AddFundEntry,
        is UpdateFundEntry, is RemoveFundEntry, is RemoveTransfer,
        -> Role.LEAD

        is AddHolding -> ownIf(op.partyId == me.id && band.event(op.eventId)?.participant(me.id) != null)
        is UpdateHolding -> ownIf(band.event(op.eventId)?.holdings?.firstOrNull { it.id == op.holdingId }?.partyId == me.id)
        is RemoveHolding -> ownIf(band.event(op.eventId)?.holdings?.firstOrNull { it.id == op.holdingId }?.partyId == me.id)

        is RecordTransfer -> ownIf(ownsParty(band, me, op.from) || ownsParty(band, me, op.to))
        is SettleSuggestions -> ownIf(op.partyId != null && ownsParty(band, me, op.partyId))

        is AddCost -> ownIf(op.memberId == me.id)
        is UpdateCost -> ownCost(band, me, op.costId)
        is DeleteCost -> ownCost(band, me, op.costId)
        is AddAttachment -> ownCost(band, me, op.costId)
        is RemoveAttachment -> ownCost(band, me, op.costId)
    }

    private fun reverseRole(op: Operation): Role = when (op) {
        is RecordTransfer, is SettleSuggestions -> Role.LEAD
        is Undo -> reverseRole(op.original)
        else -> Role.VIEWER
    }

    /** A member acts for themselves; the treasurer also acts for the band fund. */
    private fun ownsParty(band: Band, me: Member, partyId: String) =
        partyId == me.id || (partyId == BAND_FUND && band.treasurerMemberId == me.id)

    private fun ownIf(isOwn: Boolean) = if (isOwn) Role.MEMBER else Role.LEAD

    private fun ownCost(band: Band, me: Member, costId: String): Role {
        val cost = band.cost(costId) ?: return Role.LEAD
        return ownIf(cost.memberId == me.id && !CostLedger.isLockedForOwner(band, cost))
    }

    private fun forbidden(reason: RejectionReason, message: String) = Rejection(RejectionCode.FORBIDDEN, message, reason)
}
