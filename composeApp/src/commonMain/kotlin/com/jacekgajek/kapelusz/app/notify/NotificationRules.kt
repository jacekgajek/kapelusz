package com.jacekgajek.kapelusz.app.notify

import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Transfer
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

    /** How late a reminder may still be shown (Android may deliver inexact alarms up to about an hour late). */
    const val LATE_HOURS = 2

    private fun lateSince(now: LocalDateTime): LocalDateTime =
        now.toInstant(TimeZone.UTC).minus(LATE_HOURS.hours).toLocalDateTime(TimeZone.UTC)

    /**
     * Reminders still to come for upcoming, non-archived events the user takes part in, in all bands. Also those due in
     * the last [LATE_HOURS] hours that were not [shown] yet (ITERATION_3 R7): Android may deliver an alarm late, and
     * planning again in between must not drop it.
     */
    fun reminders(bands: List<Band>, userId: String, days: Set<Int>, time: LocalTime, now: LocalDateTime, shown: Set<String> = emptySet()): List<Reminder> =
        bands.flatMap { band ->
            val me = band.memberOfUser(userId) ?: return@flatMap emptyList()
            band.events.filter { !it.archived && it.participant(me.id) != null }.flatMap { event ->
                days.mapNotNull { daysBefore ->
                    val at = event.date.minus(DatePeriod(days = daysBefore)).atTime(time)
                    if (at < lateSince(now)) return@mapNotNull null
                    Reminder(
                        band.id, event.id, event.name, event.date, daysBefore, at,
                        event.agenda.filter { it.time != null }.minByOrNull { it.minutesOfEventDay ?: 0 },
                        Places.navigateUrl(event),
                    ).takeUnless { it.at <= now && it.key in shown }
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
