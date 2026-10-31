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

// Settling together (ITERATION_5 §7): two members settling as one party, each acting for themselves.

/** [fromMemberId] invites [toMemberId] to settle together. */
@Serializable
@SerialName("InviteToSettleTogether")
data class InviteToSettleTogether(val fromMemberId: Id, val toMemberId: Id) : PairOp

/** The inviter takes the invitation back. */
@Serializable
@SerialName("CancelSettleTogether")
data class CancelSettleTogether(val fromMemberId: Id, val toMemberId: Id) : PairOp

/** The invited member says yes: from now on the two settle together. */
@Serializable
@SerialName("AcceptSettleTogether")
data class AcceptSettleTogether(val fromMemberId: Id, val toMemberId: Id) : PairOp

/** The invited member says no. */
@Serializable
@SerialName("DeclineSettleTogether")
data class DeclineSettleTogether(val fromMemberId: Id, val toMemberId: Id) : PairOp

/** [memberId] stops settling together with [partnerId]. */
@Serializable
@SerialName("StopSettlingTogether")
data class StopSettlingTogether(val memberId: Id, val partnerId: Id) : PairOp
