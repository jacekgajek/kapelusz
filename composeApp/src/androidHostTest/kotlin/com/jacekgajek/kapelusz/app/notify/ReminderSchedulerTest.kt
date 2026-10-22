package com.jacekgajek.kapelusz.app.notify

import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
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

    @Test
    fun debtReminderTextsInBothLanguages() {
        fun texts(owed: List<Owed>, language: String) =
            Notifier.debtTexts(DebtReminder("b", "gig", "Club gig", LocalDate(2026, 9, 12), 0, LocalDateTime(2026, 9, 26, 18, 0), "PLN", owed), language)
                .let { (title, text) -> title.replace('\u00A0', ' ') to text.replace('\u00A0', ' ') }
        val one = listOf(Owed("Jacek", Money(10000)))
        assertEquals("You still owe Jacek 100.00 PLN" to "from Club gig (Sat 12 Sep)", texts(one, "en"))
        assertEquals("Do oddania: 100,00 zł dla: Jacek" to "Club gig (sob. 12.09)", texts(one, "pl"))
        val two = listOf(Owed("Jacek", Money(10000)), Owed(null, Money(2000)))
        assertEquals("You still owe money from Club gig (Sat 12 Sep)" to "Jacek 100.00 PLN\nthe band fund 20.00 PLN", texts(two, "en"))
        assertEquals("Nierozliczone: Club gig (sob. 12.09)" to "100,00 zł dla: Jacek\n20,00 zł do kasy zespołowej", texts(two, "pl"))
    }
}

