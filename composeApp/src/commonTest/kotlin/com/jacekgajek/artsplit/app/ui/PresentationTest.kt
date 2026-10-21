package com.jacekgajek.artsplit.app.ui

import com.jacekgajek.artsplit.app.data.AppSettings
import com.jacekgajek.artsplit.app.ui.model.MyNumber
import com.jacekgajek.artsplit.app.ui.model.eventSummaries
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.initials
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.SetEventArchived
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PresentationTest {

    @Test
    fun formatsMoneyPerLanguage() {
        assertEquals("1 234,50 zł", formatMoney(Money(123450), "PLN", "pl"))
        assertEquals("1,234.50 PLN", formatMoney(Money(123450), "PLN", "en"))
        assertEquals("−0,05 EUR", formatMoney(Money(-5), "EUR", "pl"))
        assertEquals("1,000,000.00 USD", formatMoney(Money(100_000_000), "USD", "en"))
    }

    @Test
    fun formatsDatesPerLanguage() {
        assertEquals("13.09.2026", formatDate(LocalDate(2026, 9, 13), "pl"))
        assertEquals("03 Feb 2026", formatDate(LocalDate(2026, 2, 3), "en"))
    }

    @Test
    fun initialsOfNames() {
        assertEquals("AB", initials("Alice Bob Carol"))
        assertEquals("H", initials("helper"))
    }

    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
    }

    @Test
    fun summariesShowMyNumberAndRespectFilters() {
        val band = Band.create("b", "Band", "PLN", "alice", "Alice", "u").apply(
            AddMember("bob", "Bob"),
            CreateEvent("old", "Old", LocalDate(2026, 1, 1), participants = listOf(Participant("bob"))),
            CreateEvent("gig", "Gig", LocalDate(2026, 9, 13), participants = listOf(Participant("alice"), Participant("bob"))),
            AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
            CreateEvent("archived", "Archived", LocalDate(2026, 5, 1), participants = listOf(Participant("alice"))),
            SetEventArchived("archived", true),
        )
        val alice = band.member("alice")

        val all = eventSummaries(band, alice, AppSettings())
        assertEquals(listOf("gig", "old"), all.map { it.event.id })
        assertEquals(MyNumber.Get(Money(5000)), all.first().myNumber)
        assertEquals(MyNumber.NotParticipating, all.last().myNumber)

        assertEquals(listOf("gig"), eventSummaries(band, alice, AppSettings(onlyMyEvents = true)).map { it.event.id })
        assertEquals(
            listOf("gig", "archived", "old"),
            eventSummaries(band, alice, AppSettings(showArchived = true)).map { it.event.id },
        )
        assertEquals(MyNumber.HandOver(Money(5000)), eventSummaries(band, band.member("bob"), AppSettings()).first().myNumber)
    }
}
