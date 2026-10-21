package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.history
import com.jacekgajek.artsplit.app.generated.history_empty
import com.jacekgajek.artsplit.app.generated.history_unavailable
import com.jacekgajek.artsplit.app.generated.show_history
import com.jacekgajek.artsplit.app.ui.components.SectionTitle
import com.jacekgajek.artsplit.app.ui.screens.HistoryLine
import com.jacekgajek.artsplit.app.ui.screens.HistoryRow
import com.jacekgajek.artsplit.app.ui.screens.historyMonth
import com.jacekgajek.artsplit.app.ui.screens.toLines
import org.jetbrains.compose.resources.stringResource

/** How far back the event history looks; older changes are in the band-wide history. */
private const val MONTHS = 3

/** Changes to this event, newest first; loaded on demand. */
@Composable
fun HistorySection(context: EventContext, graph: AppGraph) {
    var shown by remember(context.event.id) { mutableStateOf(false) }
    var lines by remember(context.event.id) { mutableStateOf<List<HistoryLine>?>(null) }
    var failed by remember(context.event.id) { mutableStateOf(false) }
    SectionTitle(stringResource(Res.string.history))
    if (!shown) {
        TextButton(onClick = { shown = true }) { Text(stringResource(Res.string.show_history)) }
        return
    }
    LaunchedEffect(context.event.id) {
        runCatching {
            (MONTHS - 1 downTo 0).flatMap { offset -> graph.api.history(context.band.id, historyMonth(offset)).entries }
        }.onSuccess { entries ->
            lines = entries.flatMap { it.toLines() }.filter { it.envelope.op.eventId == context.event.id }.asReversed()
        }.onFailure { failed = true }
    }
    val loaded = lines
    Column {
        when {
            failed -> Text(stringResource(Res.string.history_unavailable), color = MaterialTheme.colorScheme.error)
            loaded == null -> Text("…")
            loaded.isEmpty() -> Text(stringResource(Res.string.history_empty))
            else -> loaded.forEach { HistoryRow(context.band, it) }
        }
    }
}
