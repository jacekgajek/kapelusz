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

// Who takes part in an event, and with what share.

@Serializable
@SerialName("AddParticipant")
data class AddParticipant(
    override val eventId: Id,
    val memberId: Id,
    val weight: Int = DEFAULT_WEIGHT,
    val fixedShare: Money? = null,
    val percent: Int? = null,
) : LineupOp

@Serializable
@SerialName("AddGuest")
data class AddGuest(
    override val eventId: Id,
    val guestId: Id,
    val name: String,
    val weight: Int = DEFAULT_WEIGHT,
    val fixedShare: Money? = null,
    val percent: Int? = null,
) : LineupOp

@Serializable
@SerialName("RenameGuest")
data class RenameGuest(override val eventId: Id, val guestId: Id, val name: String) : LineupOp

@Serializable
@SerialName("SetWeight")
data class SetWeight(override val eventId: Id, val partyId: Id, val weight: Int) : LineupOp

/**
 * A fixed amount the participant takes off the top instead of a share by weight (ITERATION_3 §2.4), or a [percent] of
 * the profit (ITERATION_7 §6); neither goes back to the weight. [weight], when set, is applied in the same step, so
 * going back to a different weight is one change to undo.
 */
@Serializable
@SerialName("SetFixedShare")
data class SetFixedShare(
    override val eventId: Id,
    val partyId: Id,
    val amount: Money?,
    val weight: Int? = null,
    val percent: Int? = null,
) : LineupOp

@Serializable
@SerialName("RemoveParticipant")
data class RemoveParticipant(override val eventId: Id, val partyId: Id) : LineupOp

/**
 * The picker's line-up (ITERATION_8 §1.3): participants [add]ed with their shares, members or the new [guests] (from
 * From an event…, each with its participant in [add]); participants (members or guests) [remove]d. All or nothing, as one
 * change.
 */
@Serializable
@SerialName("ChangeLineup")
data class ChangeLineup(
    override val eventId: Id,
    val add: List<Participant> = emptyList(),
    val remove: List<Id> = emptyList(),
    val guests: List<Guest> = emptyList(),
) : LineupOp
