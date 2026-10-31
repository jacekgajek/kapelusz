package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role

/**
 * Deleting a member added by mistake: only while nothing in the band refers to them, so nothing else
 * changes. Who did take part is moved to the former members instead. An app account is unlinked first (a separate,
 * not undoable change), so the member itself can be brought back by Undo, without the account.
 */
object MemberDeletion {
    fun possible(band: Band, memberId: Id): Boolean {
        val member = band.member(memberId) ?: return false
        if (member.role == Role.OWNER || band.treasurerMemberId == member.id) return false
        // Settling together, or asked to: either side.
        if (member.settlesWith != null || member.invitedToSettle != null) return false
        if (band.members.any { it.settlesWith == member.id || it.invitedToSettle == member.id }) return false
        if (band.costs.any { it.memberId == member.id }) return false
        if (band.fundEntries.any { it.paidBy == member.id }) return false
        return band.events.none { event ->
            event.participant(member.id) != null ||
                event.participants.any { it.addedBy == member.id } ||
                event.holdings.any { it.partyId == member.id || it.recordedBy == member.id } ||
                event.transfers.any { it.from == member.id || it.to == member.id || it.recordedBy == member.id }
        }
    }
}
