package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.PartyLine
import com.jacekgajek.kapelusz.settlement.SettleTogether
import com.jacekgajek.kapelusz.settlement.Settlement
import com.jacekgajek.kapelusz.text.sortedByName
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

/**
 * As a party of the settlement: with a partner settling together, the pair's number (ITERATION_5 §7). An artist sees only
 * their own, what their partner keeps for them counting as received (ITERATION_6 §11).
 */
fun myNumber(event: Event, settlement: Settlement, me: Member?): MyNumber {
    if (me == null || event.participant(me.id) == null) return MyNumber.NotParticipating
    val delta = if (me.role == Role.ARTIST) {
        val line = settlement.line(me.id) ?: return MyNumber.NotParticipating
        // Either way round: the partner holds what the artist is owed, or the artist got what the partner is owed.
        val partner = settlement.partners[me.id]
        when {
            line.delta.isNegative -> line.delta + SettleTogether.keptByPartner(settlement, me.id)
            partner != null -> line.delta - SettleTogether.keptByPartner(settlement, partner)
            else -> line.delta
        }
    } else {
        (settlement.party(me.id) ?: return MyNumber.NotParticipating).delta
    }
    return when {
        delta.isNegative -> MyNumber.Get(-delta)
        delta.isPositive -> MyNumber.HandOver(delta)
        else -> MyNumber.Even
    }
}

/** Event list rows, newest first, filtered by the user's list settings. */
fun eventSummaries(view: BandView, me: Member?, showArchived: Boolean, onlyMyEvents: Boolean): List<EventSummary> = view.band.events
    .filter { showArchived || !it.archived }
    .map { event ->
        val settlement = view.settlement(event)
        EventSummary(event, settlement, myNumber(event, settlement, me))
    }
    .filter { !onlyMyEvents || it.isMine }
    .sortedWith(EVENT_ORDER)

/** The event list's order: newest first. */
internal val EVENT_ORDER: Comparator<EventSummary> = compareByDescending<EventSummary> { it.event.date }.thenByDescending { it.event.updatedAt }

/**
 * Of what [line] still has to hand over, the part its party keeps as the band fund's holder (ITERATION_6 §8): the
 * fund's share not handed over yet, up to what they hold too much. Zero for anyone but the treasurer (or their pair).
 */
fun fundKeptBy(band: Band, settlement: Settlement, line: PartyLine): Money {
    if (!line.delta.isPositive || band.treasurerMemberId !in line.partyIds) return Money.ZERO
    val fundStillGets = settlement.line(BAND_FUND)?.delta?.takeIf { it.isNegative }?.let { -it } ?: return Money.ZERO
    return minOf(fundStillGets, line.delta)
}

/** Display name of a party: member, guest, or the band fund (with its holder). */
fun partyName(band: Band, event: Event?, partyId: Id, fundLabel: String): String = when (partyId) {
    BAND_FUND -> band.treasurerMemberId?.let { band.member(it)?.name }?.let { "$fundLabel ($it)" } ?: fundLabel
    else -> band.member(partyId)?.name ?: event?.guest(partyId)?.name ?: partyId
}

/**
 * Display name of a settlement party (ITERATION_5 §7): a pair settling together as both names, joined by [pairPattern]
 * (`%1$s and %2$s`), in alphabetical order (ITERATION_8 §2); anyone else as [partyName].
 */
fun partyName(band: Band, event: Event?, settlement: Settlement, partyId: Id, fundLabel: String, pairPattern: String): String {
    val ids = settlement.party(partyId)?.partyIds ?: listOf(partyId)
    if (ids.size == 1) return partyName(band, event, partyId, fundLabel)
    val names = ids.map { partyName(band, event, it, fundLabel) }.sortedByName { it }
    return pairPattern.replace("%1\$s", names[0]).replace("%2\$s", names[1])
}

fun initials(name: String): String = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/**
 * The "Continue" card (ITERATION_2 §3.1): the most recent unsettled event, i.e. not archived and open, at a loss, or a
 * draft whose date has passed (a gig with no money entered yet). Future events are not "unsettled business". Latest
 * date first; on the same date, the one edited last.
 */
fun unsettledEvent(view: BandView, today: LocalDate): Event? = view.band.events
    .filter { !it.archived && it.date <= today }
    .filter { event ->
        when (view.settlement(event).status) {
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
