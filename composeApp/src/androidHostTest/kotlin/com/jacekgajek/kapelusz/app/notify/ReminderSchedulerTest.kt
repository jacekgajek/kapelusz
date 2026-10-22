package com.jacekgajek.kapelusz.app.notify

import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The reminder scheduler's bookkeeping and texts (ITERATION_3 R14). Runs as a JVM host test. */
class ReminderSchedulerTest {
    @Test
    fun scheduledRemindersAreListedSoonestFirst() {
        val entries = setOf(ReminderLog.scheduledEntry(300, "Club gig"), ReminderLog.scheduledEntry(100, "Wedding | evening"), "broken")
        assertEquals(listOf(100L to "Wedding | evening", 300L to "Club gig"), ReminderLog.scheduled(entries), "a name may hold the separator")
    }

    @Test
    fun shownRemindersAreRememberedNewestFirstAndCapped() {
        var entries = emptySet<String>()
        (1..ReminderLog.MAX_SHOWN + 5).forEach { n -> entries = ReminderLog.withShown(entries, "key$n", n.toLong()) }
        val shown = ReminderLog.shownAt(entries)
        assertEquals(ReminderLog.MAX_SHOWN, shown.size)
        assertEquals(null, shown["key1"], "the oldest are dropped")
        assertEquals((ReminderLog.MAX_SHOWN + 5).toLong(), shown["key${ReminderLog.MAX_SHOWN + 5}"])
        // Shown again: one entry, with the newer time.
        assertEquals(1000L, ReminderLog.shownAt(ReminderLog.withShown(entries, "key100", 1000))["key100"])
        assertEquals(ReminderLog.MAX_SHOWN, ReminderLog.shownAt(ReminderLog.withShown(entries, "key100", 1000)).size)
    }

    @Test
    fun unreadableEntriesAreSkipped() {
        assertEquals(mapOf("k" to 5L), ReminderLog.shownAt(setOf("5|k", "x|y", "")))
    }

    @Test
    fun reminderTextsInBothLanguages() {
        val date = LocalDate(2026, 9, 12) // a Saturday
        val arrival = AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0))
        assertEquals("Tomorrow: Club gig" to "Sat 12 Sep · Arrival 17:00", Notifier.reminderTexts("Club gig", 1, date, arrival, "en"))
        assertEquals("Jutro: Club gig" to "sob. 12.09 · Przyjazd 17:00", Notifier.reminderTexts("Club gig", 1, date, arrival, "pl"))
        assertEquals("In 3 days: Club gig" to "Sat 12 Sep", Notifier.reminderTexts("Club gig", 3, date, null, "en"))
        assertEquals("Za 3 dni: Club gig" to "sob. 12.09", Notifier.reminderTexts("Club gig", 3, date, null, "pl"))
    }
}
