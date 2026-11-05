package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.jacekgajek.kapelusz.app.util.PdfExporter.PdfGenerateOptions
import com.jacekgajek.kapelusz.model.Member
import kotlinx.datetime.LocalDate

/**
 * The summary screen's state that outlives a recomposition: the dialog open over it, with the PDF export's choices.
 * Scoped to the screen's back-stack entry. The period itself is a setting, and the summary a derived value the screen
 * recomputes.
 */
class SummaryViewModel : ViewModel() {
    var dialog by mutableStateOf<SummaryDialog?>(null)
        private set

    fun chooseRange() {
        dialog = SummaryDialog.ChoosingRange
    }

    /** The export dialog, with the choices as they were last time it was open. */
    fun export() {
        dialog = SummaryDialog.Exporting(exportForm)
    }

    fun invite(member: Member) {
        dialog = SummaryDialog.Inviting(member)
    }

    fun dismiss() {
        dialog = null
    }

    private val exportForm = PdfExportForm()
}

/** What is open over the summary: one at a time. */
sealed interface SummaryDialog {
    data object ChoosingRange : SummaryDialog

    data class Exporting(val form: PdfExportForm) : SummaryDialog

    data class Inviting(val member: Member) : SummaryDialog
}

/** What the PDF export covers: the events, each on its own page or not, and the fund; at least one of the two. */
@Stable
class PdfExportForm {
    var events by mutableStateOf(true)
    var bandFund by mutableStateOf(true)
    var eventsOnSeparatePages by mutableStateOf(true)

    val valid: Boolean get() = events || bandFund

    /** The export as chosen, over [from]..[to] (none for everything). */
    fun options(from: LocalDate?, to: LocalDate?) = PdfGenerateOptions(events, bandFund, eventsOnSeparatePages, from, to)
}
