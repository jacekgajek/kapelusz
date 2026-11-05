package com.jacekgajek.kapelusz.app.util

import com.jacekgajek.kapelusz.app.util.PdfExporter.PdfGenerateOptions
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.settlement.FundRow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

/** What the PDF export takes from the band: the events and the fund within the dates asked, as a statement. */
class PdfExportRangeTest :
    ExpectSpec({
        var opCount = 0

        fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
            (Engine.apply(band, Actor.System, op, "op${opCount++}", 1) as ApplyResult.Applied).band
        }

        fun pln(text: String) = Money.parse(text)

        fun options(from: LocalDate?, to: LocalDate?): PdfGenerateOptions {
            val all = true
            return PdfGenerateOptions(events = all, bandFund = all, eventsOnSeparatePages = false, from, to)
        }

        /**
         * Three events a month apart, the middle one handing 100 to the fund; three fund entries on the same dates,
         * 10, 20 and 30. Alice is the treasurer.
         */
        val band = Band.create("b", "Band", "PLN", "alice", "Alice", "u-alice").apply(
            AddMember("bob", "Bob"),
            SetTreasurer("alice"),
            CreateEvent("jan", "January", LocalDate(2026, 1, 10), participants = listOf(Participant("alice"), Participant("bob"))),
            CreateEvent("feb", "February", LocalDate(2026, 2, 10), participants = listOf(Participant("alice"), Participant("bob"))),
            CreateEvent("mar", "March", LocalDate(2026, 3, 10), participants = listOf(Participant("alice"), Participant("bob"))),
            SetFundAmount("feb", pln("100")),
            RecordTransfer("feb", "t1", "alice", BAND_FUND, pln("100")),
            AddFundEntry("f-jan", LocalDate(2026, 1, 10), "January dues", pln("10"), paidBy = "bob"),
            AddFundEntry("f-feb", LocalDate(2026, 2, 10), "February dues", pln("20"), paidBy = "bob"),
            AddFundEntry("f-mar", LocalDate(2026, 3, 10), "March dues", pln("30"), paidBy = "bob"),
        )

        expect("no dates: everything, by date") {
            val export = PdfExporter.Export(band, options(from = null, to = null))
            export.events.map { it.id } shouldBe listOf("jan", "feb", "mar")
            export.fundRows.map { it.key } shouldBe listOf("entry-f-jan", "event-feb", "entry-f-feb", "entry-f-mar")
            withClue("the whole fund: 100 from February and the three entries") { export.balance shouldBe pln("160") }
            withClue("no start: nothing before it") { export.opening shouldBe Money.ZERO }
            export.closing shouldBe pln("160")
        }

        expect("a range takes the events, the fund's rows and the balance within it") {
            val export = PdfExporter.Export(band, options(from = LocalDate(2026, 2, 1), to = LocalDate(2026, 2, 28)))
            export.events.map { it.id } shouldBe listOf("feb")
            export.fundRows.map { it.key } shouldBe listOf("event-feb", "entry-f-feb")
            withClue("February's 100 from the event and its 20 entry, nothing from the months around") {
                export.balance shouldBe pln("120")
            }
            withClue("January's 10 was in the fund before") { export.opening shouldBe pln("10") }
            withClue("the statement adds up") { export.closing shouldBe pln("130") }
        }

        expect("a row on the period's last day is in its change and its closing balance") {
            val export = PdfExporter.Export(band, options(from = LocalDate(2026, 2, 1), to = LocalDate(2026, 2, 10)))
            export.fundRows.map { it.key } shouldBe listOf("event-feb", "entry-f-feb")
            export.balance shouldBe pln("120")
            export.closing shouldBe pln("130")
        }

        expect("the range's ends are in it") {
            val export = PdfExporter.Export(band, options(from = LocalDate(2026, 1, 10), to = LocalDate(2026, 3, 10)))
            export.events.map { it.id } shouldBe listOf("jan", "feb", "mar")
            export.balance shouldBe pln("160")
            export.opening shouldBe Money.ZERO
            export.closing shouldBe pln("160")
        }

        expect("a range with nothing in it is empty, with a balance of zero") {
            val export = PdfExporter.Export(band, options(from = LocalDate(2025, 1, 1), to = LocalDate(2025, 12, 31)))
            export.events.shouldBeEmpty()
            export.fundRows.shouldBeEmpty()
            export.balance shouldBe Money.ZERO
            export.closing shouldBe Money.ZERO
        }

        expect("a period after everything opens and closes on the full fund") {
            val export = PdfExporter.Export(band, options(from = LocalDate(2026, 4, 1), to = LocalDate(2026, 4, 30)))
            export.opening shouldBe pln("160")
            export.balance shouldBe Money.ZERO
            export.closing shouldBe pln("160")
        }

        expect("an event's row is in the fund only once it is for the fund") {
            val rows = PdfExporter.Export(band, options(from = null, to = null)).fundRows.filterIsInstance<FundRow.OfEvent>()
            rows.map { it.event.id } shouldBe listOf("feb")
        }
    })
