package com.jacekgajek.artsplit.app.ui.model

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.auth.Permissions
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.model.Holding
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.AddGuest
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.RecordTransfer
import com.jacekgajek.artsplit.ops.RemoveTransfer
import com.jacekgajek.artsplit.ops.SetRefund
import com.jacekgajek.artsplit.ops.SettleSuggestions
import com.jacekgajek.artsplit.ops.TransferOwnership
import com.jacekgajek.artsplit.ops.UpdateEvent
import com.jacekgajek.artsplit.ops.UpdateHolding
import kotlinx.datetime.LocalDate

/**
 * What the current user may do, for showing only the actions that would be accepted. Every answer comes from the
 * shared [Permissions] (the same rules the server enforces), asked with a representative operation.
 */
class Can(private val band: Band, private val userId: String) {
    private val actor = Actor.User(userId)
    private val meId: Id? = band.memberOfUser(userId)?.id

    fun allowed(op: Operation): Boolean = Permissions.isAllowed(band, actor, op)

    fun createEvent() = allowed(CreateEvent(PROBE, PROBE, PROBE_DATE))
    fun editEvent(event: Event) = allowed(UpdateEvent(event.id))
    fun manageParticipants(event: Event) = allowed(AddGuest(event.id, PROBE, PROBE))
    fun editRefunds(event: Event) = allowed(SetRefund(event.id, PROBE, Money.ZERO))
    fun settleAll(event: Event) = allowed(SettleSuggestions(event.id))
    fun settleFor(event: Event, partyId: Id) = allowed(SettleSuggestions(event.id, partyId))
    fun recordTransfer(event: Event, from: Id, to: Id) = allowed(RecordTransfer(event.id, PROBE, from, to, Money(1)))
    fun removeTransfer(event: Event) = allowed(RemoveTransfer(event.id, PROBE))

    /** Parties the user may record collected money for in this event (themselves, or anyone for leads). */
    fun holdingParties(event: Event): List<Id> =
        event.participants.map { it.partyId }.filter { allowed(AddHolding(event.id, PROBE, it, Money(1), IncomeSource.OTHER)) }

    fun editHolding(event: Event, holding: Holding) = allowed(UpdateHolding(event.id, holding.id))
    fun addCost() = meId != null && allowed(AddCost(PROBE, meId, Money(1), PROBE_DATE))
    fun manageRoster() = allowed(AddMember(PROBE, PROBE))
    fun transferOwnership() = band.memberOfUser(userId)?.role == Role.OWNER && allowed(TransferOwnership(PROBE))

    private companion object {
        const val PROBE = "probe"
        val PROBE_DATE = LocalDate(2000, 1, 1)
    }
}
