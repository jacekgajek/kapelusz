package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.auth.Permissions
import com.jacekgajek.kapelusz.domain.Artists
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RefuseCost
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.ops.UpdateHolding
import kotlinx.datetime.LocalDate

/**
 * What the current user may do, for showing only the actions that would be accepted. Every answer comes from the
 * shared [Permissions] (the same rules the server enforces), asked with a representative operation.
 */
class Can(private val band: Band, private val userId: String) {
    private val actor = Actor.User(userId)
    private val meId: Id? = band.memberOfUser(userId)?.id

    fun allowed(op: Operation): Boolean = Permissions.isAllowed(band, actor, op)

    /** Sees only their own events, costs and payouts (ITERATION_4 §1.1). */
    val artist: Boolean get() = band.isArtist(userId)
    fun refuseCost() = allowed(RefuseCost(PROBE))
    fun setMemberShare() = allowed(SetMemberShare(PROBE, DEFAULT_WEIGHT, null))

    fun createEvent() = allowed(CreateEvent(PROBE, PROBE, PROBE_DATE))
    fun archiveEvents() = allowed(SetEventArchived(PROBE, true))
    fun editEvent(event: Event) = open(event) && allowed(UpdateEvent(event.id))
    fun manageParticipants(event: Event) = open(event) && allowed(AddGuest(event.id, PROBE, PROBE))
    fun editRefunds(event: Event) = open(event) && allowed(SetRefund(event.id, PROBE, Money.ZERO))
    fun settleAll(event: Event) = open(event) && allowed(SettleSuggestions(event.id))
    fun recordTransfer(event: Event, from: Id, to: Id) = open(event) && allowed(RecordTransfer(event.id, PROBE, from, to, Money(1)))

    /** A suggestion's transfer, where a pair settling together is either partner (ITERATION_5 §7). */
    fun settleSuggestion(event: Event, from: Id, to: Id) = open(event) && allowed(RecordTransfer(event.id, PROBE, from, to, Money(1), together = true))
    fun removeTransfer(event: Event) = open(event) && allowed(RemoveTransfer(event.id, PROBE))

    /** Parties the user may record collected money for in this event (themselves, or anyone for leads). */
    fun holdingParties(event: Event): List<Id> =
        if (!open(event)) emptyList() else event.participants.map { it.partyId }.filter { !Artists.isArtist(band, it) && allowed(AddHolding(event.id, PROBE, it, Money(1), IncomeSource.OTHER)) }

    /** A fee the organiser still owes (ITERATION_4 §2.2): leads record it and mark it received. */
    fun recordAwaited(event: Event) = open(event) && allowed(AddHolding(event.id, PROBE, ORGANISER, Money(1), IncomeSource.CONTRACT))

    /** Who an awaited fee can have been paid to: someone in the event who may hold money, or the band fund when it is on. */
    fun receiveParties(event: Event): List<Id> =
        if (!open(event) || !allowed(ReceiveAwaited(event.id, PROBE, PROBE, PROBE, Money(1)))) emptyList()
        else event.participants.map { it.partyId }.filter { !Artists.isArtist(band, it) } + listOfNotNull(BAND_FUND.takeIf { band.treasurerMemberId != null })

    fun editHolding(event: Event, holding: Holding) = open(event) && allowed(UpdateHolding(event.id, holding.id))
    fun addCost() = meId != null && allowed(AddCost(PROBE, meId, Money(1), PROBE_DATE))

    /** A cost for this event: some active participant the user may add a cost for (leads: anyone in it; members: themselves). */
    fun addCostIn(event: Event) = open(event) && event.participants.any { participant ->
        band.member(participant.partyId)?.archived == false && allowed(AddCost(PROBE, participant.partyId, Money(1), PROBE_DATE))
    }
    fun manageRoster() = allowed(AddMember(PROBE, PROBE))
    fun transferOwnership() = band.memberOfUser(userId)?.role == Role.OWNER && allowed(TransferOwnership(PROBE))

    /** Archived events are read-only until restored (ITERATION_3 R10). */
    private fun open(event: Event) = !event.archived

    private companion object {
        const val PROBE = "probe"
        val PROBE_DATE = LocalDate(2000, 1, 1)
    }
}

/** Artists see only their own events, costs and payouts (ITERATION_4 §1.1). Hidden in the UI only, see its §1.4. */
fun Band.isArtist(userId: String?): Boolean = userId != null && memberOfUser(userId)?.role == Role.ARTIST
