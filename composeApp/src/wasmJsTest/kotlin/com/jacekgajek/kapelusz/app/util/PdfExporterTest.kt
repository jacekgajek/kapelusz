package com.jacekgajek.kapelusz.app.util

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
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
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.view.BandView
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/** The export runs in the browser, with the app's strings, for a band with every kind of row an event's pages show. */
@OptIn(ExperimentalTestApi::class)
class PdfExporterTest {
    @Test
    fun eventsExport() = runComposeUiTest {
        var environment: ResourceEnvironment? = null
        setContent { environment = rememberResourceEnvironment() }
        waitForIdle()
        val options = PdfExporter.PdfGenerateOptions(events = true)
        val pdf = PdfExporter.generatePdf(BandView(band()), options, environment!!, "en", LocalDate(2026, 11, 2))
        val content = pdf.toByteArray().decodeToString()
        assertTrue(content.startsWith("%PDF-"), "a PDF")
        // Every run in embedded Inter: pdfkmp's built-in Helvetica garbles "·", "½" and the no-break space in amounts.
        assertTrue("/BaseFont /Inter-Regular" in content && "/BaseFont /Inter-Bold" in content, "Inter embedded")
        assertFalse("Helvetica" in content, "no built-in Helvetica")
    }

    private fun band(): Band {
        var band = Band.create("b1", "Żółte Łabędzie", "PLN", "m-alice", "Alice", "u1")
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
        )
        ops.forEachIndexed { i, op -> band = (Engine.apply(band, Actor.System, op, "op$i", 0) as ApplyResult.Applied).band }
        return band
    }
}
