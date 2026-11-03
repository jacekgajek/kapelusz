package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.format.formatMonth
import com.jacekgajek.kapelusz.app.ui.text.TemplateDraft
import com.jacekgajek.kapelusz.app.ui.text.toItem
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.view.BandView
import com.jacekgajek.kapelusz.view.MyNumber
import com.jacekgajek.kapelusz.view.eventSummaries
import com.jacekgajek.kapelusz.view.initials
import com.jacekgajek.kapelusz.view.unsettledEvent
import com.jacekgajek.kapelusz.view.upcomingEvent
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
    (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
}

class PresentationTest :
    ExpectSpec({
        expect("formats money per language") {
            formatMoney(Money(123450), "PLN", "pl") shouldBe "1 234,50 zł"
            formatMoney(Money(123450), "PLN", "en") shouldBe "1,234.50 PLN"
            formatMoney(Money(-5), "EUR", "pl") shouldBe "−0,05 EUR"
            formatMoney(Money(100_000_000), "USD", "en") shouldBe "1,000,000.00 USD"
        }

        expect("formats dates per language") {
            formatDate(LocalDate(2026, 9, 13), "pl") shouldBe "13.09.2026"
            formatDate(LocalDate(2026, 2, 3), "en") shouldBe "03 Feb 2026"
        }

        expect("formats months per language") {
            formatMonth("2026-10", "en") shouldBe "October 2026"
            formatMonth("2027-01", "pl") shouldBe "styczeń 2027"
        }

        expect("initials of names") {
            initials("Alice Bob Carol") shouldBe "AB"
            initials("helper") shouldBe "H"
        }

        expect("summaries show my number and respect filters") {
            val band = Band.create("b", "Band", "PLN", "alice", "Alice", "u").apply(
                AddMember("bob", "Bob"),
                CreateEvent("old", "Old", LocalDate(2026, 1, 1), participants = listOf(Participant("bob"))),
                CreateEvent("gig", "Gig", LocalDate(2026, 9, 13), participants = listOf(Participant("alice"), Participant("bob"))),
                AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
                CreateEvent("archived", "Archived", LocalDate(2026, 5, 1), participants = listOf(Participant("alice"))),
                SetEventArchived("archived", true),
            )
            val alice = band.member("alice")

            val all = eventSummaries(BandView(band), alice, showArchived = false, onlyMyEvents = false)
            all.map { it.event.id } shouldBe listOf("gig", "old")
            all.first().myNumber shouldBe MyNumber.Get(Money(5000))
            all.last().myNumber shouldBe MyNumber.NotParticipating

            eventSummaries(BandView(band), alice, showArchived = false, onlyMyEvents = true).map { it.event.id } shouldBe listOf("gig")
            eventSummaries(BandView(band), alice, showArchived = true, onlyMyEvents = false).map { it.event.id } shouldBe
                listOf("gig", "archived", "old")
            eventSummaries(BandView(band), band.member("bob"), showArchived = false, onlyMyEvents = false).first().myNumber shouldBe
                MyNumber.HandOver(Money(5000))
        }

        expect("a paired artist sees only their own number") {
            // Carol, an artist, settles together with Bob. Three shares of 100.
            // [before] is applied while Carol is still a member.
            fun band(holder: String, vararg before: Operation) = Band.create("b", "Band", "PLN", "alice", "Alice", "u").apply(
                AddMember("bob", "Bob"),
                AddMember("carol", "Carol"),
                CreateEvent(
                    "gig",
                    "Gig",
                    LocalDate(2026, 9, 13),
                    participants = listOf(Participant("alice"), Participant("bob"), Participant("carol")),
                ),
                AddHolding("gig", "h", holder, Money(30000), IncomeSource.DOOR),
                *before,
            ).let { band ->
                band.copy(
                    members = band.members.map {
                        when (it.id) {
                            "bob" -> it.copy(userId = "ub", settlesWith = "carol")
                            "carol" -> it.copy(userId = "uc", role = Role.ARTIST, settlesWith = "bob")
                            else -> it
                        }
                    },
                )
            }
            fun number(band: Band, id: String) = eventSummaries(BandView(band), band.member(id), showArchived = false, onlyMyEvents = false).single().myNumber

            val bobHolds = band("bob")
            withClue("Bob keeps her 100") { number(bobHolds, "carol") shouldBe MyNumber.Even }
            withClue("the pair's number: Alice's 100") { number(bobHolds, "bob") shouldBe MyNumber.HandOver(Money(10000)) }
            val aliceHolds = band("alice")
            withClue("her own 100, not the pair's 200") { number(aliceHolds, "carol") shouldBe MyNumber.Get(Money(10000)) }
            // Paid the pair's 200 while still a member: she holds Bob's 100 for him, nothing to hand back.
            val paidToCarol = band("alice", RecordTransfer("gig", "t", "alice", "carol", Money(20000)))
            number(paidToCarol, "carol") shouldBe MyNumber.Even
        }

        expect("continue card shows the most recent unsettled event") {
            val today = LocalDate(2026, 10, 21)
            val base = Band.create("b", "Band", "PLN", "me", "Me", "user").apply(AddMember("bob", "Bob"))
            val open = CreateEvent("open", "Open gig", LocalDate(2026, 9, 1), participants = listOf(Participant("me"), Participant("bob")))
            val draftPast = CreateEvent("draft", "Past draft", LocalDate(2026, 10, 1), participants = listOf(Participant("me")))
            val future = CreateEvent("future", "Future", LocalDate(2026, 11, 1), participants = listOf(Participant("me")))
            val band = base.apply(open, AddHolding("open", "h", "me", Money(1000), IncomeSource.DOOR), draftPast, future)
            withClue("a past draft is unfinished business, and newer than the open gig") {
                unsettledEvent(BandView(band), today)?.id shouldBe "draft"
            }
            unsettledEvent(BandView(band.apply(SetEventArchived("draft", true))), today)?.id shouldBe "open"
            withClue("future events are not unsettled business") { unsettledEvent(BandView(base.apply(future)), today).shouldBeNull() }
            val draftToday = BandView(base.apply(CreateEvent("today", "Tonight", today)))
            withClue("a draft today is not overdue yet") { unsettledEvent(draftToday, today).shouldBeNull() }
        }

        expect("upcoming card prefers my next gig") {
            val today = LocalDate(2026, 10, 21)
            val band = Band.create("b", "Band", "PLN", "me", "Me", "user").apply(
                AddMember("bob", "Bob"),
                CreateEvent("bobs", "Bob's gig", LocalDate(2026, 10, 25), participants = listOf(Participant("bob"))),
                CreateEvent("mine", "My gig", LocalDate(2026, 11, 2), participants = listOf(Participant("me"))),
                CreateEvent("past", "Past", LocalDate(2026, 10, 1), participants = listOf(Participant("me"))),
            )
            val me = band.member("me")
            upcomingEvent(band, me, today)?.id shouldBe "mine"
            withClue("not in any: the band's nearest") { upcomingEvent(band, band.member("nobody"), today)?.id shouldBe "bobs" }
            upcomingEvent(band.apply(SetEventArchived("mine", true)), me, today)?.id shouldBe "bobs"
            withClue("today counts") { upcomingEvent(band, me, LocalDate(2026, 11, 2))?.id shouldBe "mine" }
        }

        expect("one card per event and the latest edit wins on the same date") {
            val today = LocalDate(2026, 10, 21)
            val base = Band.create("b", "Band", "PLN", "me", "Me", "user")
            val tonight = base.apply(
                AddMember("bob", "Bob"),
                CreateEvent("tonight", "Tonight", today, participants = listOf(Participant("me"), Participant("bob"))),
                AddHolding("tonight", "h", "me", Money(100), IncomeSource.DOOR),
            )
            val unsettled = unsettledEvent(BandView(tonight), today)
            withClue("open tonight: unsettled business") { unsettled?.id shouldBe "tonight" }
            withClue("not also upcoming") {
                upcomingEvent(tonight, tonight.member("me"), today, except = unsettled?.id).shouldBeNull()
            }

            // Two unsettled drafts on the same past date: the one edited last.
            val sameDay = LocalDate(2026, 10, 1)
            val twoDrafts = base.apply(CreateEvent("a", "A", sameDay), CreateEvent("b", "B", sameDay))
            val edited = twoDrafts.copy(events = twoDrafts.events.map { if (it.id == "a") it.copy(updatedAt = 99) else it })
            unsettledEvent(BandView(edited), today)?.id shouldBe "a"
        }

        expect("template editor keeps built-in entries when moved or neighbours removed") {
            val labels = mapOf(AgendaKind.ARRIVAL to "Przyjazd", AgendaKind.CONCERT_START to "Początek koncertu")
            val drafts = listOf(TemplateDraft(AgendaKind.CONCERT_START, "Początek koncertu"), TemplateDraft(AgendaKind.ARRIVAL, "Przyjazd"))
            drafts.map { it.toItem(labels::getValue) } shouldBe
                listOf(AgendaTemplateItem(AgendaKind.CONCERT_START), AgendaTemplateItem(AgendaKind.ARRIVAL))
            drafts.drop(1).map { it.toItem(labels::getValue) } shouldBe listOf(AgendaTemplateItem(AgendaKind.ARRIVAL))
            withClue("renamed: custom") {
                TemplateDraft(AgendaKind.ARRIVAL, " Zbiórka ").toItem(labels::getValue) shouldBe AgendaTemplateItem(label = "Zbiórka")
            }
        }
    })
