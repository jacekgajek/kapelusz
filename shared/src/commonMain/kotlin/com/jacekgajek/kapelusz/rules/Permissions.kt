package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AttachmentOp
import com.jacekgajek.kapelusz.ops.BandOp
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.CostOp
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.EventDetailsOp
import com.jacekgajek.kapelusz.ops.EventMoneyOp
import com.jacekgajek.kapelusz.ops.FundOp
import com.jacekgajek.kapelusz.ops.HoldingOp
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.LineupOp
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.PairOp
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.RenameAttachment
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.RosterOp
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetBankAccount
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundBankAccount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundDefault
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.ops.TransferOp
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnknownOperation
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.settlement.SettleTogether

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
        // Its times are the server's: asked for with DELETE /bands/{id} (ITERATION_7 §9), never pushed.
        if (op is ScheduleBandDeletion) return forbidden(RejectionReason.ROLE_REQUIRED, "Only the owner deletes the band, through the server")
        // Settling together is everyone's own decision (ITERATION_5 §7), and where one is paid their own (§8): nobody,
        // leaders included, decides it for others.
        settlesFor(op)?.let { if (it != me.id) return forbidden(RejectionReason.ROLE_REQUIRED, "Only for yourself") }

        val required = requiredRole(band, me, op)
        return if (me.role.atLeast(required)) null else forbidden(RejectionReason.ROLE_REQUIRED, "Requires role $required")
    }

    fun isAllowed(band: Band, actor: Actor, op: Operation): Boolean = check(band, actor, op) == null

    /** Lowest role allowed to perform [op] for this actor; "own" exceptions lower it to MEMBER (or ARTIST). */
    private fun requiredRole(band: Band, me: Member, op: Operation): Role = when (op) {
        // Undo is allowed to whoever may make the original change (ITERATION_2 §7.2), and the reverse one where that
        // needs more: a member may record their own transfer, but only leads remove transfers.
        is Undo -> maxOf(requiredRole(band, me, op.original), reverseRole(band, me, op.original))

        // Never applied (ITERATION_8 §2); the reducer refuses it for anyone.
        is UnknownOperation -> Role.OWNER

        is BandOp -> bandRole(band, me, op)

        is RosterOp -> rosterRole(op)

        // Only for oneself (checked in [check]), from artists up (ITERATION_6 §11).
        is PairOp -> Role.ARTIST

        is EventDetailsOp, is LineupOp, is EventMoneyOp -> Role.LEAD

        is HoldingOp -> holdingRole(band, me, op)

        is TransferOp -> transferRole(band, me, op)

        is CostOp -> costRole(band, me, op)

        is FundOp -> fundRole(band, me, op)

        is AttachmentOp -> attachmentRole(band, me, op)
    }

    private fun bandRole(band: Band, me: Member, op: BandOp): Role = when (op) {
        is TransferOwnership -> Role.OWNER

        // Asking for the deletion is the owner's, through the server; any leader keeps the band (ITERATION_7 §9).
        is ScheduleBandDeletion -> Role.OWNER

        is CancelBandDeletion -> Role.LEAD

        // Where to pay into the fund (ITERATION_7 §7): its holder, who keeps the money, and leads.
        is SetFundBlikPhone, is SetFundBankAccount -> ownIf(band.treasurerMemberId == me.id)

        is RenameBand, is SetTreasurer, is DisableBandFund, is SetAgendaTemplate, is SetFundDefault -> Role.LEAD
    }

    private fun rosterRole(op: RosterOp): Role = when (op) {
        // Artists are paid too, so they give their number as well; viewers are never paid (ITERATION_5 §8). Only for
        // oneself (checked in [check]).
        is SetBlikPhone, is SetBankAccount -> Role.ARTIST

        is AddMember, is RenameMember, is SetMemberArchived, is DeleteMember, is SetMemberRole, is SetMemberShare,
        is LinkUser, is UnlinkUser,
        -> Role.LEAD
    }

    private fun holdingRole(band: Band, me: Member, op: HoldingOp): Role = when (op) {
        is AddHolding -> ownIf(op.partyId == me.id && band.event(op.eventId)?.participant(me.id) != null)

        // An organiser's fee paid to someone: they say it arrived (ITERATION_7 §3.3), the treasurer for the fund.
        is ReceiveAwaited -> ownIf(ownsParty(band, me, op.partyId))

        is UpdateHolding, is RemoveHolding -> ownIf(ownHolding(band, me, op.eventId, op.holdingId))
    }

    private fun transferRole(band: Band, me: Member, op: TransferOp): Role = when (op) {
        // Either partner settles a pair's transfer (ITERATION_5 §7). Artists too mark their own as paid or received
        // (ITERATION_7 §3.2), only as themselves: a pair's transfer is split by what each holds, so it could move their
        // partner's money.
        is RecordTransfer -> ownIf(
            ownsParty(band, me, op.from) || ownsParty(band, me, op.to) || (op.together && partnerIn(band, me, op.eventId).let { it != null && (it == op.from || it == op.to) }),
            if (op.together) Role.MEMBER else Role.ARTIST,
        )

        is SettleSuggestions -> ownIf(op.partyId != null && (ownsParty(band, me, op.partyId) || op.partyId == partnerIn(band, me, op.eventId)))

        // Who recorded a transfer takes it back, e.g. a Paid tapped by mistake (ITERATION_7 §3.3); leads any.
        is RemoveTransfer -> ownIf(band.event(op.eventId)?.transfers?.firstOrNull { it.id == op.transferId }?.recordedBy.let { it != null && it == me.id }, Role.ARTIST)
    }

    private fun costRole(band: Band, me: Member, op: CostOp): Role = when (op) {
        // Artists too look after their own costs (ITERATION_4 §1.1); money held stays from members up.
        is AddCost -> ownIf(op.memberId == me.id, Role.ARTIST)

        is UpdateCost, is DeleteCost -> ownCost(band, me, op.costId)
    }

    /**
     * The treasurer keeps the fund's ledger (ITERATION_7 §3.3): they hold its money. Refunding a cost from it decides
     * about someone's cost, so stays with leads.
     */
    private fun fundRole(band: Band, me: Member, op: FundOp): Role = when (op) {
        is AddFundEntry -> fundEntry(band, me, op.costId)

        is UpdateFundEntry, is RemoveFundEntry ->
            band.fundEntries.firstOrNull { it.id == op.entryId }?.let { fundEntry(band, me, it.costId) } ?: Role.LEAD
    }

    private fun attachmentRole(band: Band, me: Member, op: AttachmentOp): Role = when (op) {
        is AddAttachment, is RenameAttachment -> ownAttachments(band, me, op.owner, adding = true)
        is RemoveAttachment -> ownAttachments(band, me, op.owner)
    }

    /** Whom [me] settles together with in this event: only where both take part (ITERATION_5 §7). */
    private fun partnerIn(band: Band, me: Member, eventId: String): String? = band.event(eventId)?.let { SettleTogether.partners(band, it)[me.id] }

    /** The member a settling-together operation is for: the only one who may perform it. */
    private fun settlesFor(op: Operation): String? = when (op) {
        is InviteToSettleTogether -> op.fromMemberId
        is CancelSettleTogether -> op.fromMemberId
        is AcceptSettleTogether -> op.toMemberId
        is DeclineSettleTogether -> op.toMemberId
        is StopSettlingTogether -> op.memberId
        is SetBlikPhone -> op.memberId
        is SetBankAccount -> op.memberId
        else -> null
    }

    private fun reverseRole(band: Band, me: Member, op: Operation): Role = when (op) {
        is RecordTransfer, is SettleSuggestions -> Role.LEAD

        // Taking a file back removes it: a locked cost's owner adds receipts but removes none (ITERATION_7 §3.3).
        is AddAttachment -> requiredRole(band, me, RemoveAttachment(op.owner, op.attachment.id))

        is Undo -> reverseRole(band, me, op.original)

        else -> Role.VIEWER
    }

    /** A member acts for themselves; the treasurer also acts for the band fund. */
    private fun ownsParty(band: Band, me: Member, partyId: String) = partyId == me.id || (partyId == BAND_FUND && band.treasurerMemberId == me.id)

    private fun ownIf(isOwn: Boolean, own: Role = Role.MEMBER) = if (isOwn) own else Role.LEAD

    /**
     * Money [me] holds and recorded (or nobody did, before that was kept): not what a lead recorded for them, e.g. a fee
     * they received, which keeps the lead's record and its contract (ITERATION_7 §3.3).
     */
    private fun ownHolding(band: Band, me: Member, eventId: Id, holdingId: Id): Boolean = band.event(eventId)?.holdings?.firstOrNull { it.id == holdingId }?.let { it.partyId == me.id && (it.recordedBy == null || it.recordedBy == me.id) } == true

    /** The fund's holder for a plain entry; leads for any, and for one refunding a cost. */
    private fun fundEntry(band: Band, me: Member, costId: Id?) = ownIf(band.treasurerMemberId == me.id && costId == null)

    /**
     * Files follow what they belong to (ITERATION_4 §2.1): a cost's as the cost, a collected entry's as the entry (its
     * holder, or leads), the event's as the event (leads). [adding] (or renaming) a receipt to one's own cost stays
     * allowed once it is locked (ITERATION_7 §3.3): a file changes no money; removing one doesn't.
     */
    private fun ownAttachments(band: Band, me: Member, owner: AttachmentOwner, adding: Boolean = false): Role = when (owner) {
        is AttachmentOwner.OfCost -> if (adding && band.cost(owner.costId)?.memberId == me.id) Role.ARTIST else ownCost(band, me, owner.costId)
        is AttachmentOwner.OfHolding -> ownIf(ownHolding(band, me, owner.eventId, owner.holdingId))
        is AttachmentOwner.OfEvent -> Role.LEAD
    }

    private fun ownCost(band: Band, me: Member, costId: String): Role {
        val cost = band.cost(costId) ?: return Role.LEAD
        return ownIf(cost.memberId == me.id && !CostLedger.isLockedForOwner(band, cost), Role.ARTIST)
    }

    private fun forbidden(reason: RejectionReason, message: String) = Rejection(RejectionCode.FORBIDDEN, message, reason)
}
