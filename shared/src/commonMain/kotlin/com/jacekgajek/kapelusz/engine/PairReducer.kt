package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.PairOp
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.settlement.SettleTogether

/** Settling together (I5 §7): invitations and pairs. Pure state transitions and their validation; see [Reducer]. */
internal object PairReducer {
    fun apply(band: Band, op: PairOp): Band = when (op) {
        is InviteToSettleTogether -> {
            val from = band.member(op.fromMemberId).orNotFound { "Member ${op.fromMemberId}" }
            val to = band.member(op.toMemberId).orNotFound { "Member ${op.toMemberId}" }
            conflictIf(!SettleTogether.mayInvite(band, from, to), RejectionReason.CANNOT_SETTLE_TOGETHER) { "${from.id} cannot invite ${to.id} to settle together" }
            band.updateMember(from.id) { it.copy(invitedToSettle = to.id) }
        }

        is CancelSettleTogether -> band.dropInvite(op.fromMemberId, op.toMemberId)

        is AcceptSettleTogether -> {
            band.dropInvite(op.fromMemberId, op.toMemberId)
                .updateMember(op.fromMemberId) { it.copy(settlesWith = op.toMemberId) }
                .updateMember(op.toMemberId) { it.copy(settlesWith = op.fromMemberId) }
                // Both still may settle together (§7.2).
                .also { conflictIf(SettleTogether.tidy(it) != it, RejectionReason.CANNOT_SETTLE_TOGETHER) { "They cannot settle together" } }
        }

        is DeclineSettleTogether -> band.dropInvite(op.fromMemberId, op.toMemberId)

        is StopSettlingTogether -> {
            conflictIf(band.member(op.memberId)?.settlesWith != op.partnerId, RejectionReason.NOT_FOUND) { "${op.memberId} does not settle with ${op.partnerId}" }
            SettleTogether.keepSettled(band, op.memberId, op.partnerId)
                .updateMember(op.memberId) { it.copy(settlesWith = null) }.updateMember(op.partnerId) { it.copy(settlesWith = null) }
        }
    }

    /** An invitation to settle together, answered or taken back. */
    private fun Band.dropInvite(fromMemberId: Id, toMemberId: Id): Band {
        conflictIf(member(fromMemberId)?.invitedToSettle != toMemberId, RejectionReason.NOT_FOUND) { "No invitation from $fromMemberId to $toMemberId" }
        return updateMember(fromMemberId) { it.copy(invitedToSettle = null) }
    }
}
