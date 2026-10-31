package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Id
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The band itself: its name, who holds the fund and its payment details, the agenda template, ownership and deletion.

@Serializable
@SerialName("RenameBand")
data class RenameBand(val name: String) : BandOp

@Serializable
@SerialName("SetTreasurer")
data class SetTreasurer(val memberId: Id) : BandOp

/** Turns the band fund off (only while it holds nothing); choosing a holder with [SetTreasurer] turns it on again. */
@Serializable
@SerialName("DisableBandFund")
data object DisableBandFund : BandOp

/**
 * The owner's request to delete the band, applied only by the server (`DELETE /bands/{id}`), which
 * sets the times by its own clock.
 */
@Serializable
@SerialName("ScheduleBandDeletion")
data class ScheduleBandDeletion(val deletion: BandDeletion) : BandOp

/** A leader keeps the band: the deletion asked for doesn't happen. */
@Serializable
@SerialName("CancelBandDeletion")
data object CancelBandDeletion : BandOp

/** The BLIK number to pay into the band fund; null removes it. */
@Serializable
@SerialName("SetFundBlikPhone")
data class SetFundBlikPhone(val phone: String?) : BandOp

/** The bank account to pay into the band fund; null removes it. */
@Serializable
@SerialName("SetFundBankAccount")
data class SetFundBankAccount(val account: String?) : BandOp

@Serializable
@SerialName("TransferOwnership")
data class TransferOwnership(val toMemberId: Id) : BandOp

/** The agenda times new events get (leader+); existing events keep theirs. */
@Serializable
@SerialName("SetAgendaTemplate")
data class SetAgendaTemplate(val items: List<AgendaTemplateItem>) : BandOp

/** The band's fund default for new events, applied to [events] too, as a member's default share is. */
@Serializable
@SerialName("SetFundDefault")
data class SetFundDefault(val cut: FundCut?, val events: List<Id> = emptyList()) : BandOp
