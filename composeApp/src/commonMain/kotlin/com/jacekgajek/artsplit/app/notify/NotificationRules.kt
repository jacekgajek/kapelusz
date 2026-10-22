package com.jacekgajek.artsplit.app.notify

import com.jacekgajek.artsplit.domain.Places
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Transfer
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.minus

/** A gig reminder to show at [at] (local time), [daysBefore] days before the event. */
data class Reminder(
    val bandId: Id,
    val eventId: Id,
    val eventName: String,
    val date: LocalDate,
    val daysBefore: Int,
    val at: LocalDateTime,
    /** The first agenda time set, mentioned in the text ("arrival 17:00"). */
    val firstTime: AgendaTime?,
    /** Google Maps for the gig's place, for a Navigate action (ITERATION_2 §4.4). */
    val navigateUrl: String? = null,
) {
    /** Stable per event and lead time, so rescheduling replaces rather than duplicates. */
    val key: String get() = "$bandId/$eventId/$daysBefore"
}

/** New transfers involving the user in one event, recorded by others since the last check. */
data class TransferNotice(val bandId: Id, val event: Event, val transfers: List<Transfer>, val recordedBy: List<Id>, val meId: Id)

/** The result of [NotificationRules.checkTransfers]. */
data class TransferCheck(val notices: List<TransferNotice>, val seen: Set<String>, val baselined: Set<Id>)

/** Which notifications exist, from the bands on this device (ITERATION_2 §5); free of Android, so it can be tested. */
object NotificationRules {
    val LEAD_DAYS = listOf(1, 2, 3, 7)

    /** Reminders still to come for upcoming, non-archived events the user takes part in, in all bands. */
    fun reminders(bands: List<Band>, userId: String, days: Set<Int>, time: LocalTime, now: LocalDateTime): List<Reminder> =
        bands.flatMap { band ->
            val me = band.memberOfUser(userId) ?: return@flatMap emptyList()
            band.events.filter { !it.archived && it.participant(me.id) != null }.flatMap { event ->
                days.mapNotNull { daysBefore ->
                    val at = event.date.minus(DatePeriod(days = daysBefore)).atTime(time)
                    if (at <= now) return@mapNotNull null
                    Reminder(
                        band.id, event.id, event.name, event.date, daysBefore, at,
                        event.agenda.filter { it.time != null }.minByOrNull { it.minutesOfEventDay ?: 0 },
                        Places.navigateUrl(event),
                    )
                }
            }
        }.sortedBy { it.at }

    /** Every transfer on this device, as `band/transfer` keys: what has been seen so far. */
    fun transferKeys(bands: List<Band>): Set<String> =
        bands.flatMapTo(mutableSetOf()) { band -> band.events.flatMap { event -> event.transfers.map { "${band.id}/${it.id}" } } }

    /**
     * Transfers not in [seen] that involve the user (sending, receiving, or the band fund they hold) and that someone
     * else recorded; grouped per event.
     */
    fun newTransfers(bands: List<Band>, userId: String, seen: Set<String>): List<TransferNotice> = bands.flatMap { band ->
        val me = band.memberOfUser(userId) ?: return@flatMap emptyList()
        val mine = setOfNotNull(me.id, BAND_FUND.takeIf { band.treasurerMemberId == me.id })
        band.events.mapNotNull { event ->
            val fresh = event.transfers.filter { "${band.id}/${it.id}" !in seen && (it.from in mine || it.to in mine) && it.recordedBy != me.id }
            if (fresh.isEmpty()) null else TransferNotice(band.id, event, fresh, fresh.mapNotNull { it.recordedBy }.distinct(), me.id)
        }
    }

    /**
     * One check for new transfers: what to notify, and what counts as seen afterwards. A band seen for the first time
     * (just joined, or the first run on this device) only records what it has, so its history never floods the
     * user; seen keys of bands missing from this run are kept for when they come back.
     */
    fun checkTransfers(bands: List<Band>, userId: String, seen: Set<String>, baselined: Set<Id>): TransferCheck {
        val present = bands.map { it.id }.toSet()
        val notices = newTransfers(bands.filter { it.id in baselined }, userId, seen)
        val keptForAbsent = seen.filterTo(mutableSetOf()) { it.substringBefore('/') !in present }
        return TransferCheck(notices, transferKeys(bands) + keptForAbsent, baselined + present)
    }

    /** For the permission prompt: the nearest upcoming event the user takes part in, with who added them. */
    fun firstUpcomingGig(bands: List<Band>, userId: String, today: LocalDate): Pair<Band, Event>? =
        bands.flatMap { band ->
            val me = band.memberOfUser(userId) ?: return@flatMap emptyList()
            band.events.filter { !it.archived && it.date >= today && it.participant(me.id) != null }.map { band to it }
        }.minByOrNull { it.second.date }
}
