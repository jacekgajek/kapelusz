package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The roster: members, their roles, accounts, default shares and payment details.

@Serializable
@SerialName("AddMember")
data class AddMember(override val memberId: Id, val name: String) : RosterOp

@Serializable
@SerialName("RenameMember")
data class RenameMember(override val memberId: Id, val name: String) : RosterOp

@Serializable
@SerialName("SetMemberArchived")
data class SetMemberArchived(override val memberId: Id, val archived: Boolean) : RosterOp

@Serializable
@SerialName("DeleteMember")
data class DeleteMember(override val memberId: Id) : RosterOp

/**
 * A member's default share (ITERATION_4 §2.3): [weight], or [fixedShare] or [percent] (ITERATION_7 §6) when set. Also
 * applied, in the same step, to their share in [events] (chosen by the user: events not settled where they still had
 * the old default).
 */
@Serializable
@SerialName("SetMemberShare")
data class SetMemberShare(
    override val memberId: Id,
    val weight: Int,
    val fixedShare: Money?,
    val events: List<Id> = emptyList(),
    val percent: Int? = null,
) : RosterOp

@Serializable
@SerialName("SetMemberRole")
data class SetMemberRole(override val memberId: Id, val role: Role) : RosterOp

/** Applied by the server when a user accepts an invite. */
@Serializable
@SerialName("LinkUser")
data class LinkUser(override val memberId: Id, val userId: String, val role: Role) : RosterOp

@Serializable
@SerialName("UnlinkUser")
data class UnlinkUser(override val memberId: Id) : RosterOp

/** The member's own BLIK phone number (ITERATION_5 §8); null removes it. */
@Serializable
@SerialName("SetBlikPhone")
data class SetBlikPhone(override val memberId: Id, val phone: String?) : RosterOp

/** The member's own bank account number (ITERATION_7 §7); null removes it. */
@Serializable
@SerialName("SetBankAccount")
data class SetBankAccount(override val memberId: Id, val account: String?) : RosterOp
