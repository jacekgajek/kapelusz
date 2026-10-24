package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.Settlement
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import kotlinx.datetime.LocalDate

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

/** As a party of the settlement: with a partner settling together, the pair's number (ITERATION_5 §7). */
fun myNumber(event: Event, settlement: Settlement, me: Member?): MyNumber {
    if (me == null || event.participant(me.id) == null) return MyNumber.NotParticipating
    val line = settlement.party(me.id) ?: return MyNumber.NotParticipating
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

/**
 * Display name of a settlement party (ITERATION_5 §7): a pair settling together as both names, joined by [pairPattern]
 * (`%1$s and %2$s`), in party order; anyone else as [partyName].
 */
fun partyName(band: Band, event: Event?, settlement: Settlement, partyId: Id, fundLabel: String, pairPattern: String): String {
    val ids = settlement.party(partyId)?.partyIds ?: listOf(partyId)
    if (ids.size == 1) return partyName(band, event, partyId, fundLabel)
    return pairPattern.replace("%1\$s", partyName(band, event, ids[0], fundLabel)).replace("%2\$s", partyName(band, event, ids[1], fundLabel))
}

fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/**
 * The "Continue" card (ITERATION_2 §3.1): the most recent unsettled event, i.e. not archived and open, at a loss, or a
 * draft whose date has passed (a gig with no money entered yet). Future events are not "unsettled business". Latest
 * date first; on the same date, the one edited last.
 */
fun unsettledEvent(band: Band, today: LocalDate): Event? =
    band.events
        .filter { !it.archived && it.date <= today }
        .filter { event ->
            when (SettlementCalculator.settle(band, event).status) {
                // Awaiting payment too: the organiser still owes (ITERATION_4 §2.2).
                EventStatus.OPEN, EventStatus.LOSS, EventStatus.AWAITING -> true
                EventStatus.DRAFT -> event.date < today
                EventStatus.SETTLED -> false
            }
        }
        .maxWithOrNull(compareBy<Event> { it.date }.thenBy { it.updatedAt })

/**
 * The "Upcoming" card (ITERATION_2 §3.2): the nearest non-archived event from [today] on that [me] takes part in, or,
 * if there is none, the nearest band event.
 */
fun upcomingEvent(band: Band, me: Member?, today: LocalDate, except: Id? = null): Event? {
    val upcoming = band.events.filter { !it.archived && it.date >= today && it.id != except }.sortedWith(compareBy<Event> { it.date }.thenBy { it.name })
    return upcoming.firstOrNull { event -> me != null && event.participant(me.id) != null } ?: upcoming.firstOrNull()
}
