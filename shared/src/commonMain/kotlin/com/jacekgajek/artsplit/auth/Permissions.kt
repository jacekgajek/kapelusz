package com.jacekgajek.artsplit.auth

import com.jacekgajek.artsplit.domain.CostLedger
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddAttachment
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.AddFundEntry
import com.jacekgajek.artsplit.ops.AddGuest
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.AddParticipant
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.DeleteCost
import com.jacekgajek.artsplit.ops.DeleteEvent
import com.jacekgajek.artsplit.ops.DeleteMember
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.MoveMember
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.PromoteGuest
import com.jacekgajek.artsplit.ops.RecordTransfer
import com.jacekgajek.artsplit.ops.Rejection
import com.jacekgajek.artsplit.ops.RejectionCode
import com.jacekgajek.artsplit.ops.RemoveAttachment
import com.jacekgajek.artsplit.ops.RemoveFundEntry
import com.jacekgajek.artsplit.ops.RemoveHolding
import com.jacekgajek.artsplit.ops.RemoveParticipant
import com.jacekgajek.artsplit.ops.RemoveTransfer
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.RenameGuest
import com.jacekgajek.artsplit.ops.RenameMember
import com.jacekgajek.artsplit.ops.SetEventArchived
import com.jacekgajek.artsplit.ops.SetFundAmount
import com.jacekgajek.artsplit.ops.SetMemberArchived
import com.jacekgajek.artsplit.ops.SetMemberRole
import com.jacekgajek.artsplit.ops.SetRefund
import com.jacekgajek.artsplit.ops.SetTreasurer
import com.jacekgajek.artsplit.ops.SetWeight
import com.jacekgajek.artsplit.ops.SettleSuggestions
import com.jacekgajek.artsplit.ops.TransferOwnership
import com.jacekgajek.artsplit.ops.UnlinkUser
import com.jacekgajek.artsplit.ops.UpdateCost
import com.jacekgajek.artsplit.ops.UpdateEvent
import com.jacekgajek.artsplit.ops.UpdateFundEntry
import com.jacekgajek.artsplit.ops.UpdateHolding

/** Who performs an operation. */
sealed interface Actor {
    /** An app user, identified by Google `sub`. */
    data class User(val userId: String) : Actor

    /** The server itself, e.g. accepting an invite. Allowed everything. */
    data object System : Actor
}

/**
 * The role matrix (REFINED_PLAN §3). Runs on clients (to hide actions) and on the server (to enforce them).
 * Data validity is not checked here; that is the reducer's job.
 */
object Permissions {

    fun check(band: Band, actor: Actor, op: Operation): Rejection? {
        if (actor is Actor.System) return null
        val me = band.memberOfUser((actor as Actor.User).userId)?.takeIf { !it.archived }
            ?: return forbidden("You are not an active member of this band")
        if (op is LinkUser) return forbidden("Users are linked only by accepting an invite")

        val required = requiredRole(band, me, op)
        return if (me.role.atLeast(required)) null else forbidden("Requires role $required")
    }

    fun isAllowed(band: Band, actor: Actor, op: Operation): Boolean = check(band, actor, op) == null

    /** Lowest role allowed to perform [op] for this actor; "own" exceptions lower it to MEMBER. */
    private fun requiredRole(band: Band, me: Member, op: Operation): Role = when (op) {
        is TransferOwnership -> Role.OWNER

        is RenameBand, is AddMember, is RenameMember, is MoveMember, is SetMemberArchived, is DeleteMember,
        is SetMemberRole, is UnlinkUser, is SetTreasurer, is PromoteGuest, is LinkUser,
        -> Role.ADMIN

        is CreateEvent, is UpdateEvent, is SetEventArchived, is DeleteEvent, is AddParticipant, is AddGuest,
        is RenameGuest, is SetWeight, is RemoveParticipant, is SetRefund, is SetFundAmount, is AddFundEntry,
        is UpdateFundEntry, is RemoveFundEntry, is RemoveTransfer,
        -> Role.LEAD

        is AddHolding -> ownIf(op.partyId == me.id && band.event(op.eventId)?.participant(me.id) != null)
        is UpdateHolding -> ownIf(band.event(op.eventId)?.holdings?.firstOrNull { it.id == op.holdingId }?.partyId == me.id)
        is RemoveHolding -> ownIf(band.event(op.eventId)?.holdings?.firstOrNull { it.id == op.holdingId }?.partyId == me.id)

        is RecordTransfer -> ownIf(op.from == me.id || op.to == me.id)
        is SettleSuggestions -> ownIf(op.partyId == me.id)

        is AddCost -> ownIf(op.memberId == me.id)
        is UpdateCost -> ownCost(band, me, op.costId)
        is DeleteCost -> ownCost(band, me, op.costId)
        is AddAttachment -> ownCost(band, me, op.costId)
        is RemoveAttachment -> ownCost(band, me, op.costId)
    }

    private fun ownIf(isOwn: Boolean) = if (isOwn) Role.MEMBER else Role.LEAD

    private fun ownCost(band: Band, me: Member, costId: String): Role {
        val cost = band.cost(costId) ?: return Role.LEAD
        return ownIf(cost.memberId == me.id && !CostLedger.isLockedForOwner(band, cost))
    }

    private fun forbidden(message: String) = Rejection(RejectionCode.FORBIDDEN, message)
}
