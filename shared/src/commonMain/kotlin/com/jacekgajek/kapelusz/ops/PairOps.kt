package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.Id
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Settling together (I5 §7): two members settling as one party, each acting for themselves.

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
