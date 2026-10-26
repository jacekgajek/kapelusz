package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.ui.model.MyNumber
import com.jacekgajek.kapelusz.app.ui.model.eventSummaries
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMonth
import com.jacekgajek.kapelusz.app.ui.model.initials
import com.jacekgajek.kapelusz.app.ui.model.TemplateDraft
import com.jacekgajek.kapelusz.app.ui.model.toItem
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.app.ui.model.unsettledEvent
import com.jacekgajek.kapelusz.app.ui.model.upcomingEvent
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetEventArchived
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
    fun formatsMonthsPerLanguage() {
        assertEquals("October 2026", formatMonth("2026-10", "en"))
        assertEquals("styczeń 2027", formatMonth("2027-01", "pl"))
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

    @Test
    fun aPairedArtistSeesOnlyTheirOwnNumber() {
        // ITERATION_6 §11: Carol, an artist, settles together with Bob. Three shares of 100.
        // [before] is applied while Carol is still a member.
        fun band(holder: String, vararg before: Operation) = Band.create("b", "Band", "PLN", "alice", "Alice", "u").apply(
            AddMember("bob", "Bob"),
            AddMember("carol", "Carol"),
            CreateEvent("gig", "Gig", LocalDate(2026, 9, 13), participants = listOf(Participant("alice"), Participant("bob"), Participant("carol"))),
            AddHolding("gig", "h", holder, Money(30000), IncomeSource.DOOR),
            *before,
        ).let { band ->
            band.copy(members = band.members.map {
                when (it.id) {
                    "bob" -> it.copy(userId = "ub", settlesWith = "carol")
                    "carol" -> it.copy(userId = "uc", role = Role.ARTIST, settlesWith = "bob")
                    else -> it
                }
            })
        }
        fun number(band: Band, id: String) = eventSummaries(band, band.member(id), AppSettings()).single().myNumber

        val bobHolds = band("bob")
        assertEquals(MyNumber.Even, number(bobHolds, "carol"), "Bob keeps her 100")
        assertEquals(MyNumber.HandOver(Money(10000)), number(bobHolds, "bob"), "the pair's number: Alice's 100")
        val aliceHolds = band("alice")
        assertEquals(MyNumber.Get(Money(10000)), number(aliceHolds, "carol"), "her own 100, not the pair's 200")
        // Paid the pair's 200 while still a member: she holds Bob's 100 for him, nothing to hand back.
        val paidToCarol = band("alice", RecordTransfer("gig", "t", "alice", "carol", Money(20000)))
        assertEquals(MyNumber.Even, number(paidToCarol, "carol"))
    }

    @Test
    fun continueCardShowsTheMostRecentUnsettledEvent() {
        val today = LocalDate(2026, 10, 21)
        val base = Band.create("b", "Band", "PLN", "me", "Me", "user").apply(AddMember("bob", "Bob"))
        val open = CreateEvent("open", "Open gig", LocalDate(2026, 9, 1), participants = listOf(Participant("me"), Participant("bob")))
        val draftPast = CreateEvent("draft", "Past draft", LocalDate(2026, 10, 1), participants = listOf(Participant("me")))
        val future = CreateEvent("future", "Future", LocalDate(2026, 11, 1), participants = listOf(Participant("me")))
        val band = base.apply(open, AddHolding("open", "h", "me", Money(1000), IncomeSource.DOOR), draftPast, future)
        assertEquals("draft", unsettledEvent(band, today)?.id, "a past draft is unfinished business, and newer than the open gig")
        assertEquals("open", unsettledEvent(band.apply(SetEventArchived("draft", true)), today)?.id)
        assertEquals(null, unsettledEvent(base.apply(future), today), "future events are not unsettled business")
        assertEquals(null, unsettledEvent(base.apply(CreateEvent("today", "Tonight", today)), today), "a draft today is not overdue yet")
    }

    @Test
    fun upcomingCardPrefersMyNextGig() {
        val today = LocalDate(2026, 10, 21)
        val band = Band.create("b", "Band", "PLN", "me", "Me", "user").apply(
            AddMember("bob", "Bob"),
            CreateEvent("bobs", "Bob's gig", LocalDate(2026, 10, 25), participants = listOf(Participant("bob"))),
            CreateEvent("mine", "My gig", LocalDate(2026, 11, 2), participants = listOf(Participant("me"))),
            CreateEvent("past", "Past", LocalDate(2026, 10, 1), participants = listOf(Participant("me"))),
        )
        val me = band.member("me")
        assertEquals("mine", upcomingEvent(band, me, today)?.id)
        assertEquals("bobs", upcomingEvent(band, band.member("nobody"), today)?.id, "not in any: the band's nearest")
        assertEquals("bobs", upcomingEvent(band.apply(SetEventArchived("mine", true)), me, today)?.id)
        assertEquals("mine", upcomingEvent(band, me, LocalDate(2026, 11, 2))?.id, "today counts")
    }

    @Test
    fun oneCardPerEventAndTheLatestEditWinsOnTheSameDate() {
        val today = LocalDate(2026, 10, 21)
        val base = Band.create("b", "Band", "PLN", "me", "Me", "user")
        val tonight = base.apply(
            AddMember("bob", "Bob"),
            CreateEvent("tonight", "Tonight", today, participants = listOf(Participant("me"), Participant("bob"))),
            AddHolding("tonight", "h", "me", Money(100), IncomeSource.DOOR),
        )
        val unsettled = unsettledEvent(tonight, today)
        assertEquals("tonight", unsettled?.id, "open tonight: unsettled business")
        assertEquals(null, upcomingEvent(tonight, tonight.member("me"), today, except = unsettled?.id), "not also upcoming")

        // Two unsettled drafts on the same past date: the one edited last.
        val sameDay = LocalDate(2026, 10, 1)
        val twoDrafts = base.apply(CreateEvent("a", "A", sameDay), CreateEvent("b", "B", sameDay))
        val edited = twoDrafts.copy(events = twoDrafts.events.map { if (it.id == "a") it.copy(updatedAt = 99) else it })
        assertEquals("a", unsettledEvent(edited, today)?.id)
    }

    @Test
    fun templateEditorKeepsBuiltInEntriesWhenMovedOrNeighboursRemoved() {
        val labels = mapOf(AgendaKind.ARRIVAL to "Przyjazd", AgendaKind.CONCERT_START to "Początek koncertu")
        val drafts = listOf(TemplateDraft(AgendaKind.CONCERT_START, "Początek koncertu"), TemplateDraft(AgendaKind.ARRIVAL, "Przyjazd"))
        assertEquals(listOf(AgendaTemplateItem(AgendaKind.CONCERT_START), AgendaTemplateItem(AgendaKind.ARRIVAL)), drafts.map { it.toItem(labels::getValue) })
        assertEquals(listOf(AgendaTemplateItem(AgendaKind.ARRIVAL)), drafts.drop(1).map { it.toItem(labels::getValue) })
        assertEquals(AgendaTemplateItem(label = "Zbiórka"), TemplateDraft(AgendaKind.ARRIVAL, " Zbiórka ").toItem(labels::getValue), "renamed: custom")
    }
}
