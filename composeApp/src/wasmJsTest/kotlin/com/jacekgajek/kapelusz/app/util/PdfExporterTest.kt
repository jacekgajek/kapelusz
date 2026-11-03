@file:OptIn(ExperimentalTestApi::class)

package com.jacekgajek.kapelusz.app.util

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.jacekgajek.kapelusz.app.testing.composeUiTest
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.view.BandView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/** The export runs in the browser, with the app's strings, for a band with every kind of row an event's and the fund's pages show. */
class PdfExporterTest :
    ExpectSpec({
        expect("events export") {
            composeUiTest {
                val options = PdfExporter.PdfGenerateOptions(events = true, bandFund = false, eventsOnSeparatePages = false)
                val content = environment().export(options)
                withClue("a PDF") { content shouldStartWith "%PDF-" }
                // Every run in embedded Inter: pdfkmp's built-in Helvetica garbles "·", "½" and the no-break space in amounts.
                withClue("Inter embedded") {
                    content shouldContain "/BaseFont /Inter-Regular"
                    content shouldContain "/BaseFont /Inter-Bold"
                }
                withClue("no built-in Helvetica") { content shouldNotContain "Helvetica" }
            }
        }

        // The fund's rows, a withdrawal for a travel cost among them: its category icon is drawn as a vector.
        expect("band fund export") {
            composeUiTest {
                val options = PdfExporter.PdfGenerateOptions(events = false, bandFund = true, eventsOnSeparatePages = false)
                val content = environment().export(options)
                withClue("a PDF") { content shouldStartWith "%PDF-" }
                withClue("the fund alone on one page") { pages(content) shouldBe 1 }
                withClue("no built-in Helvetica") { content shouldNotContain "Helvetica" }
            }
        }

        // Two short events share a page unless each goes on its own; the fund always starts a page of its own.
        expect("page breaks") {
            composeUiTest {
                val environment = environment()
                val short = shortBand()
                fun options(fund: Boolean, separate: Boolean): PdfExporter.PdfGenerateOptions {
                    val events = true
                    return PdfExporter.PdfGenerateOptions(events, bandFund = fund, eventsOnSeparatePages = separate)
                }
                withClue("events together") { pages(environment.export(options(fund = false, separate = false), short)) shouldBe 1 }
                withClue("an event a page") { pages(environment.export(options(fund = false, separate = true), short)) shouldBe 2 }
                withClue("and the fund's page") { pages(environment.export(options(fund = true, separate = true), short)) shouldBe 3 }
                withClue("events, then the fund") { pages(environment.export(options(fund = true, separate = false), short)) shouldBe 2 }
            }
        }
    })

/** The app's strings; content can be set once per test. */
private fun ComposeUiTest.environment(): ResourceEnvironment {
    var environment: ResourceEnvironment? = null
    setContent { environment = rememberResourceEnvironment() }
    waitForIdle()
    return environment!!
}

private suspend fun ResourceEnvironment.export(options: PdfExporter.PdfGenerateOptions, band: Band = band()): String {
    val pdf = PdfExporter.generatePdf(BandView(band), options, this, "en", LocalDate(2026, 11, 2))
    return pdf.toByteArray().decodeToString()
}

private fun pages(content: String) = Regex("/Type /Page /Parent").findAll(content).count()

private fun shortBand(): Band = listOf(
    CreateEvent("e1", "Jazz Night", LocalDate(2026, 10, 3), participants = listOf(Participant("m-alice"))),
    CreateEvent("e2", "Blues Night", LocalDate(2026, 10, 4), participants = listOf(Participant("m-alice"))),
).applyTo(Band.create("b1", "Trio", "PLN", "m-alice", "Alice", "u1"))

private fun List<Operation>.applyTo(band: Band): Band = foldIndexed(band) { i, applied, op ->
    (Engine.apply(applied, Actor.System, op, "op$i", 0) as ApplyResult.Applied).band
}

private fun band(): Band {
    val band = Band.create("b1", "Żółte Łabędzie", "PLN", "m-alice", "Alice", "u1")
    val arrival = AgendaTime("a1", AgendaKind.ARRIVAL, time = LocalTime(17, 0))
    val agenda = listOf(arrival, AgendaTime("a2", AgendaKind.CONCERT_START, time = LocalTime(20, 30)))
    val ops: List<Operation> = listOf(
        AddMember("m-bob", "Bob Gęślak"),
        CreateEvent(
            "e1",
            "Koncert w Łodzi",
            LocalDate(2026, 10, 3),
            location = "Klub Śródmieście, Łódź",
            notes = "Setlist:\n1. Intro\n2. Zażółć gęślą jaźń\n3. Encore",
            participants = listOf(Participant("m-alice"), Participant("m-bob", weight = 150)),
            agenda = agenda,
        ),
        AddGuest("e1", "g1", "Carol (sax)", fixedShare = Money(30000)),
        AddHolding("e1", "h1", "m-alice", Money(250000), IncomeSource.DOOR, note = "cash"),
        AddHolding("e1", "h2", "m-bob", Money(40000), IncomeSource.MERCH),
        AddCost("c1", "m-bob", Money(18000), LocalDate(2026, 10, 3), incurredForEventId = "e1", category = CostCategory.TRAVEL),
        SetRefund("e1", "c1", Money(12000)),
        CreateEvent("e2", "Jazz Night", LocalDate(2026, 11, 20), participants = listOf(Participant("m-alice"))),
        SetTreasurer("m-alice"),
        SetFundAmount("e1", Money.ZERO, percent = 10),
        AddFundEntry("f1", LocalDate(2026, 10, 10), "Donation", Money(50000)),
        AddCost("c2", "m-bob", Money(9000), LocalDate(2026, 10, 12), category = CostCategory.TRAVEL),
        AddFundEntry("f2", LocalDate(2026, 10, 15), "Fuel", Money(-9000), costId = "c2"),
    )
    return ops.applyTo(band)
}
