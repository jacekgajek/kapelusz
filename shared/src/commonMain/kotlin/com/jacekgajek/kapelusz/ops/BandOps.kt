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
 * The owner's request to delete the band (ITERATION_7 §9), applied only by the server (`DELETE /bands/{id}`), which
 * sets the times by its own clock.
 */
@Serializable
@SerialName("ScheduleBandDeletion")
data class ScheduleBandDeletion(val deletion: BandDeletion) : BandOp

/** A leader keeps the band: the deletion asked for doesn't happen (ITERATION_7 §9). */
@Serializable
@SerialName("CancelBandDeletion")
data object CancelBandDeletion : BandOp

/** The BLIK number to pay into the band fund (ITERATION_7 §7); null removes it. */
@Serializable
@SerialName("SetFundBlikPhone")
data class SetFundBlikPhone(val phone: String?) : BandOp

/** The bank account to pay into the band fund (ITERATION_7 §7); null removes it. */
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

/** The band's fund default for new events (ITERATION_7 §5.2), applied to [events] too, as a member's default share is. */
@Serializable
@SerialName("SetFundDefault")
data class SetFundDefault(val cut: FundCut?, val events: List<Id> = emptyList()) : BandOp
