package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.domain.BlikPhone
import com.jacekgajek.kapelusz.domain.MemberDeletion
import com.jacekgajek.kapelusz.domain.MemberShares
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.view.BandView

/** The roster: members, their roles, accounts, default shares and payment details. Pure state transitions and their validation; see [Reducer]. */
internal object RosterReducer {
    fun apply(band: Band, op: RosterOp, ctx: OpContext): Band = when (op) {
        is AddMember -> addMember(band, op)

        is RenameMember -> {
            val name = op.name.validName("Member name")
            band.validFreeName(name, except = op.memberId)
            band.updateMember(op.memberId) { it.copy(name = name) }
        }

        is SetMemberArchived -> setMemberArchived(band, op)

        is DeleteMember -> deleteMember(band, op, ctx)

        is SetMemberShare -> setMemberShare(band, op, ctx)

        is SetMemberRole -> setMemberRole(band, op)

        is LinkUser -> linkUser(band, op)

        is UnlinkUser -> unlinkUser(band, op)

        is SetBlikPhone -> {
            val phone = op.phone?.let { BlikPhone.normalize(it) }
            invalidIf(op.phone != null && phone == null, RejectionReason.INVALID_PHONE) { "Not a phone number" }
            band.updateMember(op.memberId) { it.copy(blikPhone = phone) }
        }

        is SetBankAccount -> band.updateMember(op.memberId) { it.copy(bankAccount = account(op.account)) }
    }

    private fun addMember(band: Band, op: AddMember): Band {
        invalidIf(band.member(op.memberId) != null, RejectionReason.DUPLICATE_ID) { "Member ${op.memberId} already exists" }
        val name = op.name.validName("Member name")
        band.validFreeName(name)
        return band.copy(members = band.members + Member(op.memberId, name))
    }

    /** Member names are unique, ignoring case, archived members included (ITERATION_5 §12). */
    private fun Band.validFreeName(name: String, except: Id? = null) {
        conflictIf(memberNamed(name, except) != null, RejectionReason.NAME_TAKEN) { "A member is already called $name" }
    }

    private fun setMemberArchived(band: Band, op: SetMemberArchived): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(op.archived && member.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "The owner cannot be archived" }
        invalidIf(op.archived && band.treasurerMemberId == op.memberId, RejectionReason.TREASURER_MUST_STAY) { "Choose another treasurer first" }
        // A removed member's photo leaves the band with them (the next sign-in brings it back if they return).
        return band.updateMember(op.memberId) { it.copy(archived = op.archived, pictureUrl = it.pictureUrl.takeUnless { op.archived }) }
    }

    private fun deleteMember(band: Band, op: DeleteMember, ctx: OpContext): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        // Yourself: Leave band is for that (ITERATION_6 §11a).
        conflictIf(member.id == ctx.memberId, RejectionReason.MEMBER_HAS_HISTORY) { "Not yourself; leave the band instead" }
        conflictIf(member.userId != null, RejectionReason.MEMBER_LINKED) { "Unlink the user before deleting the member" }
        conflictIf(band.treasurerMemberId == member.id, RejectionReason.TREASURER_MUST_STAY) { "Choose another treasurer first" }
        // Only who left no trace (ITERATION_7 §3.1): anyone who did take part is archived instead.
        conflictIf(!MemberDeletion.possible(band, member.id), RejectionReason.MEMBER_HAS_HISTORY) { "Something in the band refers to this member; archive instead" }
        return band.copy(members = band.members.filterNot { it.id == member.id })
    }

    private fun setMemberShare(band: Band, op: SetMemberShare, ctx: OpContext): Band {
        validateActiveMember(band, op.memberId)
        val share = validShare(op.weight, op.fixedShare, op.percent)
        val member = band.member(op.memberId)!!
        var result = band.updateMember(op.memberId) { it.copy(shareWeight = share.weight, shareFixed = share.fixed, sharePercent = share.percent) }
        // Events that changed since the user chose them (archived, settled, the member removed, e.g. on another phone)
        // are left alone rather than losing the new default with them.
        val view = BandView(band)
        for (eventId in op.events.distinct()) {
            band.event(eventId)?.takeIf { MemberShares.canApply(view, it, member) } ?: continue
            result = result.updateEvent(eventId) {
                it.copy(
                    participants = it.participants.map { p ->
                        if (p.partyId != op.memberId) {
                            p
                        } // A fixed amount or a percentage keeps the weight underneath, so switching back restores it (as in events).
                        else if (share.byWeight) {
                            share.applyTo(p)
                        } else {
                            share.applyTo(p).copy(weight = p.weight)
                        }
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
        if (op.role != member.role) validateRoleChange(band, member.id, op.role)
        return band.updateMember(op.memberId) { it.copy(role = op.role) }
    }

    private fun linkUser(band: Band, op: LinkUser): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        conflictIf(member.userId != null, RejectionReason.MEMBER_LINKED) { "Member is already linked to a user" }
        conflictIf(band.memberOfUser(op.userId) != null, RejectionReason.USER_ALREADY_MEMBER) { "User is already a member of this band" }
        invalidIf(op.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "Invites cannot grant ownership" }
        validateRoleChange(band, member.id, op.role)
        return band.updateMember(op.memberId) { it.copy(userId = op.userId, role = op.role, archived = false) }
    }

    private fun unlinkUser(band: Band, op: UnlinkUser): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(member.role == Role.OWNER, RejectionReason.OWNER_ROLE_FIXED) { "Transfer ownership before unlinking the owner" }
        // The photo and the BLIK number were the user's: they go with the account.
        return band.updateMember(op.memberId) { it.copy(userId = null, role = Role.MEMBER, pictureUrl = null, blikPhone = null, bankAccount = null) }
    }
}
