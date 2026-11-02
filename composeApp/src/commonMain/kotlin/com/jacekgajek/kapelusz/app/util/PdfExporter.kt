package com.jacekgajek.kapelusz.app.util

import androidx.compose.ui.graphics.vector.ImageVector
import com.conamobile.pdfkmp.PdfDocument
import com.conamobile.pdfkmp.dsl.ContainerScope
import com.conamobile.pdfkmp.dsl.DocumentScope
import com.conamobile.pdfkmp.dsl.PageScope
import com.conamobile.pdfkmp.font.BundledFonts
import com.conamobile.pdfkmp.geometry.Padding
import com.conamobile.pdfkmp.layout.PageBreakStrategy
import com.conamobile.pdfkmp.pdf
import com.conamobile.pdfkmp.style.PdfColor
import com.conamobile.pdfkmp.style.PdfFont
import com.conamobile.pdfkmp.unit.dp
import com.conamobile.pdfkmp.unit.sp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.app_name
import com.jacekgajek.kapelusz.app.generated.awaited_payment
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.collected_empty
import com.jacekgajek.kapelusz.app.generated.collected_money
import com.jacekgajek.kapelusz.app.generated.details
import com.jacekgajek.kapelusz.app.generated.event_files
import com.jacekgajek.kapelusz.app.generated.fund_balance
import com.jacekgajek.kapelusz.app.generated.fund_entry_outside
import com.jacekgajek.kapelusz.app.generated.fund_entry_refund_to
import com.jacekgajek.kapelusz.app.generated.organiser
import com.jacekgajek.kapelusz.app.generated.participants
import com.jacekgajek.kapelusz.app.generated.participants_guests
import com.jacekgajek.kapelusz.app.generated.pdf_events
import com.jacekgajek.kapelusz.app.generated.pdf_generated_on
import com.jacekgajek.kapelusz.app.generated.pdf_no_events
import com.jacekgajek.kapelusz.app.generated.refund_of
import com.jacekgajek.kapelusz.app.generated.refunds
import com.jacekgajek.kapelusz.app.generated.refunds_empty
import com.jacekgajek.kapelusz.app.generated.tab_gig
import com.jacekgajek.kapelusz.app.generated.tab_money
import com.jacekgajek.kapelusz.app.generated.total_awaited
import com.jacekgajek.kapelusz.app.generated.total_from_fund
import com.jacekgajek.kapelusz.app.generated.total_fund
import com.jacekgajek.kapelusz.app.generated.total_income
import com.jacekgajek.kapelusz.app.generated.total_net
import com.jacekgajek.kapelusz.app.generated.total_refunds
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.icon
import com.jacekgajek.kapelusz.app.ui.components.labelRes
import com.jacekgajek.kapelusz.app.ui.components.shareText
import com.jacekgajek.kapelusz.app.ui.components.statusLabelRes
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.text.formatAgendaTime
import com.jacekgajek.kapelusz.app.ui.text.labelRes
import com.jacekgajek.kapelusz.app.ui.text.setTimes
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.view.BandView
import com.jacekgajek.kapelusz.view.partyName
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The band's events as a PDF: for each, what its Gig and Money pages show (the Split page, who gets what, is left out).
 * Text comes in [ResourceEnvironment]'s language, so the PDF reads like the app; it is all resolved first, as the
 * PDF builder can't suspend.
 */
object PdfExporter {
    /** Rows a section may have and still be kept on one page: a longer one would not fit and is left to flow on. */
    private const val KEEP_LINES = 25

    class PdfGenerateOptions(val events: Boolean, val bandFund: Boolean, val eventsOnSeparatePages: Boolean)

    suspend fun generatePdf(
        view: BandView,
        options: PdfGenerateOptions,
        environment: ResourceEnvironment,
        language: String,
        today: LocalDate,
    ): PdfDocument {
        val text = Texts(environment, language, view.band.currency)
        val report = Report(
            title = text(Res.string.app_name),
            band = view.band.name,
            generatedOn = text(Res.string.pdf_generated_on, formatDate(today, language)),
            eventsTitle = text(Res.string.pdf_events).takeIf { options.events },
            noEvents = text(Res.string.pdf_no_events),
            headings = Headings.of(text),
            events = if (options.events) view.band.events.sortedBy { it.date }.map { eventPage(view, it, text) } else emptyList(),
            eventsOnSeparatePages = options.eventsOnSeparatePages,
            fundReport = if (options.bandFund) {
                FundReport(
                    title = text(Res.string.band_fund),
                    balanceTitle = text(Res.string.fund_balance),
                    balance = text.money(BandFund.balance(view.band)),
                    entries = run {
                        // What the balance counts: money an event has moved, not a cut it has yet to hand over.
                        val all = view.band.events.filter { !BandFund.moved(it).isZero }.map { FundRow.OfEvent(it) } +
                            view.band.fundEntries.map { FundRow.OfEntry(it) }
                        all.sortedBy { it.date }.map { fundEntryPage(view, it, text) }
                    },
                )
            } else {
                null
            },
        )
        return render(report)
    }

    private suspend fun fundEntryPage(
        view: BandView,
        row: FundRow,
        text: Texts,
    ): FundEntryPage = when (row) {
        is FundRow.OfEntry -> {
            val cost = row.entry.costId?.let { view.cost(it) }
            val isWithdraw = row.entry.amount.isNegative

            val (description, smallNote) = if (isWithdraw) {
                val costPayer = cost?.memberId?.let { view.member(it) }?.name
                row.entry.description to
                    costPayer?.let { text(Res.string.fund_entry_refund_to, it) }
            } else {
                (row.entry.paidBy?.let { view.member(it) }?.name ?: text(Res.string.fund_entry_outside)) to
                    row.entry.description
            }
            FundEntryPage(
                description = description,
                smallNote = smallNote,
                amount = text.money(row.entry.amount),
                costIcon = cost?.category?.icon(),
                date = formatDate(row.date, text.language),
            )
        }

        is FundRow.OfEvent ->
            FundEntryPage(
                description = row.event.name,
                smallNote = row.event.location?.substringBefore(","),
                amount = text.money(BandFund.moved(row.event)),
                costIcon = null,
                date = formatDate(row.date, text.language),
            )
    }
    private sealed interface FundRow {
        val date: LocalDate

        data class OfEvent(val event: Event) : FundRow {
            override val date get() = event.date
        }

        data class OfEntry(val entry: FundEntry) : FundRow {
            override val date get() = entry.date
        }
    }

    private suspend fun eventPage(view: BandView, event: Event, text: Texts): EventPage {
        val band = view.band
        val settlement = view.settlement(event)
        val fund = text(Res.string.band_fund)
        val (members, guests) = SettlementCalculator.partyOrder(band, event).partition { band.member(it) != null }
        suspend fun lines(ids: List<String>) = ids.mapNotNull { id ->
            val participant = event.participant(id) ?: return@mapNotNull null
            Line(
                partyName(band, event, id, ""),
                shareText(text.environment, text.language, participant.weight, participant.fixedShare, participant.percent, text.currency),
            )
        }
        val times = event.setTimes().map { time ->
            val label = time.kind?.let { text(it.labelRes()) } ?: time.label.orEmpty()
            "$label ${formatAgendaTime(time)}"
        }
        return EventPage(
            title = event.name,
            whenWhere = listOfNotNull(formatDate(event.date, text.language), event.location).joinToString(" · "),
            times = times.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            status = text(statusLabelRes(settlement.status, event.archived)),
            details = event.notes,
            files = event.attachments.map { it.displayName() },
            members = lines(members),
            guests = lines(guests),
            totals = buildList {
                add(Line(text(Res.string.total_income), amount = text.money(settlement.income)))
                if (event.awaited.isPositive) add(Line(text(Res.string.total_awaited), amount = text.money(event.awaited)))
                if (!settlement.fundAmount.isZero || event.fundPercent != null) {
                    val label = text(if (settlement.fundAmount.isNegative) Res.string.total_from_fund else Res.string.total_fund)
                    add(Line(label + (event.fundPercent?.let { " ($it%)" } ?: ""), amount = text.money(-settlement.fundAmount)))
                }
                if (!settlement.refundsTotal.isZero) {
                    add(Line(text(Res.string.total_refunds), amount = text.money(-settlement.refundsTotal)))
                }
            },
            net = Line(text(Res.string.total_net), amount = text.money(settlement.net)),
            collected = event.holdings.map { holding ->
                val awaited = text(Res.string.awaited_payment).takeIf { holding.awaited }
                val about = listOfNotNull(text(holding.source.labelRes()), holding.note, awaited, holding.attachments.namesOrNull())
                Line(
                    if (holding.awaited) text(Res.string.organiser) else partyName(band, event, holding.partyId, fund),
                    about.joinToString(" · "),
                    text.money(holding.amount),
                )
            },
            refunds = event.refunds.mapNotNull { refund ->
                val cost = band.cost(refund.costId) ?: return@mapNotNull null
                val about = listOfNotNull(costTitle(cost, text), formatDate(cost.date, text.language), cost.attachments.namesOrNull())
                Line(
                    band.member(cost.memberId)?.name.orEmpty(),
                    about.joinToString(" · "),
                    text.money(refund.amount),
                    text(Res.string.refund_of, text.money(cost.amount)).takeIf { refund.amount != cost.amount },
                )
            },
        )
    }

    /** As the app titles a cost: its description, else its category, else Unnamed. */
    private suspend fun costTitle(cost: Cost, text: Texts): String {
        val category = cost.category?.let { text(it.labelRes()) }
        return cost.description ?: category ?: text(Res.string.unnamed_cost)
    }

    private fun Attachment.displayName() = fileName ?: id

    private fun List<Attachment>.namesOrNull() = takeIf { it.isNotEmpty() }?.joinToString(", ") { it.displayName() }

    private fun render(report: Report): PdfDocument = pdf {
        defaultTextStyle = defaultTextStyle.copy(font = Fonts.regular)
        // Long details or many rows flow on to the next page rather than leaving a gap.
        defaultPageBreakStrategy = PageBreakStrategy.Slice
        report.eventsTitle?.let { title ->
            reportPage(report) {
                text(title) {
                    fontSize = 20.sp
                    font = Fonts.bold
                }
                if (report.events.isEmpty()) {
                    text(report.noEvents)
                } else {
                    event(report.events.first(), report.headings)
                }
                if (!report.eventsOnSeparatePages) {
                    report.events.drop(1).forEach {
                        spacer(height = 8.dp)
                        divider()
                        event(it, report.headings)
                    }
                }
            }
            if (report.eventsOnSeparatePages) {
                report.events.drop(1).forEach {
                    reportPage(report) {
                        event(it, report.headings)
                    }
                }
            }
        }
        report.fundReport?.let { fund ->
            reportPage(report) {
                text(fund.title) {
                    fontSize = 20.sp
                    font = Fonts.bold
                }
                spacer(height = 5.dp)
                row {
                    weighted(1f) {
                        text(fund.balanceTitle) {
                            fontSize = 16.sp
                        }
                    }
                    text(fund.balance) {
                        fontSize = 16.sp
                        font = Fonts.bold
                    }
                }
                spacer(height = 5.dp)
                fund.entries.forEachIndexed { index, page -> fundEntry(index, page) }
            }
        }
    }

    private fun DocumentScope.reportPage(report: Report, content: PageScope.() -> Unit) {
        page {
            spacing = 4.dp
            padding = Padding.all(80.dp)

            header {
                row {
                    weighted(1f) { text("${report.title} · ${report.band}") { font = Fonts.bold } }
                    text(report.generatedOn) { color = PdfColor.Gray }
                }
                divider()
                spacer(height = 8.dp)
            }
            footer { context ->
                text("${context.pageNumber} / ${context.totalPages}") {
                    fontSize = 9.sp
                    color = PdfColor.Gray
                }
            }
            content()
        }
    }

    private fun ContainerScope.fundEntry(index: Int, page: FundEntryPage) {
        box(
            background = if (index and 1 == 1) PdfColor.LightGray else null,
            padding = Padding.all(6.dp),
        ) {
            keepTogether {
                row {
                    weighted(1f) {
                        text(page.date + " · " + page.description) {
                            fontSize = 12.sp
                        }
                    }
                    column {
                        text(page.amount) {
                            fontSize = 12.sp
                            font = Fonts.bold
                        }
                    }
                }
                spacer(height = 3.dp)
                row {
                    page.costIcon?.let {
                        vector(it.toVectorImage(), width = 9.dp, height = 9.dp)
                        spacer(width = 2.dp)
                    }
                    page.smallNote?.let {
                        text(it) {
                            fontSize = 9.sp
                            color = PdfColor.DarkGray
                        }
                    }
                }
            }
        }
    }

    private fun ContainerScope.event(event: EventPage, headings: Headings) {
        spacer(height = 12.dp)
        // The name with its date and status: never alone at the bottom of a page.
        keepTogether {
            row {
                weighted(1f) {
                    text(event.title) {
                        fontSize = 16.sp
                        font = Fonts.bold
                    }
                }
                text(event.status) { color = PdfColor.DarkGray }
            }
            text(event.whenWhere)
            event.times?.let { text(it) { color = PdfColor.DarkGray } }
        }
        val gig = buildList {
            event.details?.let { details -> add(Section(headings.details, details.lines().size) { text(details) }) }
            if (event.files.isNotEmpty()) add(Section(headings.files, event.files.size) { bulletList(event.files) })
            if (event.members.isNotEmpty() || event.guests.isNotEmpty()) {
                add(
                    Section(headings.participants, event.members.size + event.guests.size) {
                        event.members.forEach { line(it) }
                        if (event.guests.isNotEmpty()) {
                            text(headings.guests) { color = PdfColor.DarkGray }
                            event.guests.forEach { line(it) }
                        }
                    },
                )
            }
        }
        tab(headings.gig, gig)
        val money = listOf(
            Section(null, event.totals.size + 1) {
                event.totals.forEach { line(it) }
                line(event.net, bold = true)
            },
            Section(headings.collected, event.collected.size) {
                if (event.collected.isEmpty()) text(headings.collectedEmpty) { color = PdfColor.DarkGray }
                event.collected.forEach { line(it) }
            },
            Section(headings.refunds, event.refunds.size) {
                if (event.refunds.isEmpty()) text(headings.refundsEmpty) { color = PdfColor.DarkGray }
                event.refunds.forEach { line(it) }
            },
        )
        tab(headings.money, money)
    }

    /** Part of a tab: its [title] (none for the totals) and [content], about [lines] lines long. */
    private class Section(val title: String?, val lines: Int, val content: ContainerScope.() -> Unit)

    /**
     * A page of the event, e.g. Gig: its title goes with its first section, and a section of up to [KEEP_LINES] stays
     * on one page, so no heading is left alone at the bottom of a page. A longer one (a long setlist) flows on.
     */
    private fun ContainerScope.tab(title: String, sections: List<Section>) {
        sections.forEachIndexed { index, section ->
            val heading: ContainerScope.() -> Unit = {
                if (index == 0) tabTitle(title)
                section.title?.let { sectionTitle(it) }
            }
            if (section.lines <= KEEP_LINES) {
                keepTogether {
                    heading()
                    section.content(this)
                }
            } else {
                keepTogether { heading() }
                section.content(this)
            }
        }
        if (sections.isEmpty()) tabTitle(title)
    }

    private fun ContainerScope.tabTitle(title: String) {
        spacer(height = 6.dp)
        text(title) {
            fontSize = 13.sp
            font = Fonts.bold
        }
    }

    private fun ContainerScope.sectionTitle(title: String) {
        spacer(height = 4.dp)
        text(title) {
            font = Fonts.bold
            color = PdfColor.DarkGray
        }
    }

    /** A name or label with what it is under it, and its amount at the end, as rows read in the app. */
    private fun ContainerScope.line(line: Line, bold: Boolean = false) {
        row(spacing = 12.dp) {
            weighted(1f) {
                text(line.label) { if (bold) font = Fonts.bold }
                line.detail?.takeIf { it.isNotEmpty() }?.let {
                    text(it) {
                        fontSize = 9.sp
                        color = PdfColor.DarkGray
                    }
                }
            }
            line.amount?.let { amount ->
                column {
                    text(amount) { if (bold) font = Fonts.bold }
                    line.amountNote?.let {
                        text(it) {
                            fontSize = 9.sp
                            color = PdfColor.DarkGray
                        }
                    }
                }
            }
        }
    }

    /**
     * Inter embedded as the document's own font for every run. pdfkmp's default writes text it can fit in WinAnsi
     * with the built-in Helvetica and gets bytes above 0x7F wrong (`·`, `½` and the no-break space in amounts come
     * out as `Â·`); an embedded font is always written by glyph. One instance each: fonts compare by their bytes.
     */
    private object Fonts {
        val regular by lazy { PdfFont.Custom("Inter-Regular", BundledFonts.interRegular) }
        val bold by lazy { PdfFont.Custom("Inter-Bold", BundledFonts.interBold) }
    }

    /** Strings in one language, and money and dates written the way the app writes them. */
    private class Texts(val environment: ResourceEnvironment, val language: String, val currency: String) {
        suspend operator fun invoke(resource: StringResource, vararg args: Any): String = getString(environment, resource, *args)

        fun money(money: Money) = formatMoney(money, currency, language)
    }

    private class Headings(
        val gig: String,
        val money: String,
        val details: String,
        val files: String,
        val participants: String,
        val guests: String,
        val collected: String,
        val collectedEmpty: String,
        val refunds: String,
        val refundsEmpty: String,
    ) {
        companion object {
            suspend fun of(text: Texts) = Headings(
                gig = text(Res.string.tab_gig),
                money = text(Res.string.tab_money),
                details = text(Res.string.details),
                files = text(Res.string.event_files),
                participants = text(Res.string.participants),
                guests = text(Res.string.participants_guests),
                collected = text(Res.string.collected_money),
                collectedEmpty = text(Res.string.collected_empty),
                refunds = text(Res.string.refunds),
                refundsEmpty = text(Res.string.refunds_empty),
            )
        }
    }

    private class Report(
        val title: String,
        val band: String,
        val generatedOn: String,
        /** Null when events are not exported. */
        val eventsTitle: String?,
        val noEvents: String,
        val headings: Headings,
        val events: List<EventPage>,
        val eventsOnSeparatePages: Boolean,
        val fundReport: FundReport?,
    )

    private class EventPage(
        val title: String,
        val whenWhere: String,
        val times: String?,
        val status: String,
        val details: String?,
        val files: List<String>,
        val members: List<Line>,
        val guests: List<Line>,
        val totals: List<Line>,
        val net: Line,
        val collected: List<Line>,
        val refunds: List<Line>,
    )

    private class FundReport(
        val title: String,
        val balanceTitle: String,
        val balance: String,
        val entries: List<FundEntryPage>,
    )

    private class FundEntryPage(
        val date: String,
        val costIcon: ImageVector?,
        val description: String?,
        val smallNote: String?,
        val amount: String,
    )

    private class Line(val label: String, val detail: String? = null, val amount: String? = null, val amountNote: String? = null)
}
