package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Event
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * A user's gigs as an iCalendar feed (ITERATION_2 §6), built from the current band data on every fetch so calendar apps
 * follow changes on their next refresh. Times are "floating" (no time zone): a gig at 20:00 shows at 20:00 in the
 * calendar's own zone, which is right for local gigs. Entries carry no money.
 */
object CalendarFeed {
    private const val MONTHS_BACK = 3
    private const val NEXT_DAY_BEFORE_HOUR = AgendaTime.NEXT_DAY_BEFORE_HOUR

    /** Events from all [bands] the user takes part in (or every event with [includeAll]), from 3 months back. */
    fun build(bands: List<Band>, userId: String, includeAll: Boolean, today: LocalDate, webUrl: String, stamp: LocalDateTime): String {
        val from = today.minus(DatePeriod(months = MONTHS_BACK))
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Art Split//Gigs//EN",
            "CALSCALE:GREGORIAN",
            "X-WR-CALNAME:Art Split",
        )
        for (band in bands) {
            // A removed (archived) member no longer sees the band's gigs.
            val me = band.memberOfUser(userId)?.takeUnless { it.archived } ?: continue
            for (event in band.events) {
                if (event.archived || event.date < from) continue
                if (!includeAll && event.participant(me.id) == null) continue
                lines += eventLines(band, event, webUrl, stamp)
            }
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    private fun eventLines(band: Band, event: Event, webUrl: String, stamp: LocalDateTime): List<String> {
        val times = event.agenda.filter { it.time != null }.sortedBy { it.minutesOfEventDay }
        val (start, end) = when {
            // No time: an all-day entry.
            times.isEmpty() -> return listOf(
                "BEGIN:VEVENT",
                "UID:${event.id}@artsplit",
                "DTSTAMP:${utc(stamp)}",
                "DTSTART;VALUE=DATE:${date(event.date)}",
                "DTEND;VALUE=DATE:${date(event.date.plus(DatePeriod(days = 1)))}",
            ) + commonLines(band, event, times, webUrl) + "END:VEVENT"
            // One time: an hour from it.
            times.size == 1 -> at(event.date, times.first().time!!).let { it to plusHour(it) }
            // Several: from the earliest to the latest; equal times still get an hour (DTEND must be later).
            else -> at(event.date, times.first().time!!).let { start ->
                val last = at(event.date, times.last().time!!)
                start to if (last > start) last else plusHour(start)
            }
        }
        return listOf(
            "BEGIN:VEVENT",
            "UID:${event.id}@artsplit",
            "DTSTAMP:${utc(stamp)}",
            "DTSTART:${dateTime(start)}",
            "DTEND:${dateTime(end)}",
        ) + commonLines(band, event, times, webUrl) + "END:VEVENT"
    }

    private fun commonLines(band: Band, event: Event, times: List<AgendaTime>, webUrl: String): List<String> {
        val agenda = times.joinToString("\n") { listOf(label(it), hhmm(it.time!!)).filter(String::isNotBlank).joinToString(" ") }
        // The app's address, not the event's: an event link only resolves in the band that is open.
        val description = listOfNotNull(agenda.ifEmpty { null }, event.notes, event.mapUrl, "${band.name} · $webUrl").joinToString("\n\n")
        return listOfNotNull(
            "SUMMARY:${text(event.name)}",
            event.location?.let { "LOCATION:${text(it)}" },
            "DESCRIPTION:${text(description)}",
            // Calendar apps open the place from here (ITERATION_2 §4.4).
            event.mapUrl?.let { "URL:$it" },
        )
    }

    /** The built-in times in English: the feed is the same for everyone. */
    private fun label(time: AgendaTime) = when (time.kind) {
        AgendaKind.ARRIVAL -> "Arrival"
        AgendaKind.SOUND_CHECK -> "Sound check"
        AgendaKind.CONCERT_START -> "Concert start"
        null -> time.label.orEmpty()
    }

    /** Times before 05:00 are on the next day (a gig ending after midnight). */
    private fun at(date: LocalDate, time: LocalTime): LocalDateTime =
        (if (time.hour < NEXT_DAY_BEFORE_HOUR) date.plus(DatePeriod(days = 1)) else date).atTime(time)

    private fun plusHour(start: LocalDateTime): LocalDateTime {
        val hour = start.hour + 1
        return if (hour < 24) LocalDateTime(start.date, LocalTime(hour, start.minute)) else LocalDateTime(start.date.plus(DatePeriod(days = 1)), LocalTime(0, start.minute))
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
    private fun date(d: LocalDate) = "${d.year}${two(d.month.ordinal + 1)}${two(d.day)}"
    private fun dateTime(t: LocalDateTime) = "${date(t.date)}T${two(t.hour)}${two(t.minute)}00"

    /** DTSTAMP must be UTC (RFC 5545 §3.8.7.2); [t] is UTC already. */
    private fun utc(t: LocalDateTime) = dateTime(t) + "Z"
    private fun hhmm(t: LocalTime) = "${two(t.hour)}:${two(t.minute)}"

    /** iCalendar text escaping (RFC 5545 §3.3.11). */
    private fun text(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    /** Lines longer than 75 octets continue on the next line after a space (RFC 5545 §3.1). */
    private fun fold(line: String): String {
        val bytes = line.encodeToByteArray()
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var count = 0
        var first = true
        // By code point, so a character (e.g. an emoji) is never split across lines.
        line.codePoints().forEach { codePoint ->
            val ch = String(Character.toChars(codePoint))
            val size = ch.encodeToByteArray().size
            if (count + size > (if (first) 75 else 74)) {
                out.append("\r\n ")
                count = 0
                first = false
            }
            out.append(ch)
            count += size
        }
        return out.toString()
    }
}
