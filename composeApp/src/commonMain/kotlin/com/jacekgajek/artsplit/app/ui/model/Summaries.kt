package com.jacekgajek.artsplit.app.ui.model

import com.jacekgajek.artsplit.app.data.AppSettings
import com.jacekgajek.artsplit.domain.Settlement
import com.jacekgajek.artsplit.domain.SettlementCalculator
import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.model.Money

/** The current user's position in an event: what they still get or must hand over. */
sealed interface MyNumber {
    data class Get(val amount: Money) : MyNumber
    data class HandOver(val amount: Money) : MyNumber
    data object Even : MyNumber
    data object NotParticipating : MyNumber
}

data class EventSummary(val event: Event, val settlement: Settlement, val myNumber: MyNumber) {
    val isMine: Boolean get() = myNumber != MyNumber.NotParticipating
}

fun myNumber(event: Event, settlement: Settlement, me: Member?): MyNumber {
    val line = me?.let { settlement.line(it.id) } ?: return MyNumber.NotParticipating
    if (event.participant(line.partyId) == null) return MyNumber.NotParticipating
    return when {
        line.delta.isNegative -> MyNumber.Get(-line.delta)
        line.delta.isPositive -> MyNumber.HandOver(line.delta)
        else -> MyNumber.Even
    }
}

/** Event list rows, newest first, filtered by the user's list settings. */
fun eventSummaries(band: Band, me: Member?, settings: AppSettings): List<EventSummary> =
    band.events
        .filter { settings.showArchived || !it.archived }
        .map { event ->
            val settlement = SettlementCalculator.settle(band, event)
            EventSummary(event, settlement, myNumber(event, settlement, me))
        }
        .filter { !settings.onlyMyEvents || it.isMine }
        .sortedWith(compareByDescending<EventSummary> { it.event.date }.thenByDescending { it.event.updatedAt })

/** Display name of a party: member, guest, or the band fund (with its holder). */
fun partyName(band: Band, event: Event?, partyId: Id, fundLabel: String): String = when (partyId) {
    BAND_FUND -> band.treasurerMemberId?.let { band.member(it)?.name }?.let { "$fundLabel ($it)" } ?: fundLabel
    else -> band.member(partyId)?.name ?: event?.guest(partyId)?.name ?: partyId
}

fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
