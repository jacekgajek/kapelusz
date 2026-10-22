package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.jacekgajek.artsplit.app.ui.screens.UndoDialog
import com.jacekgajek.artsplit.app.ui.screens.undoOf
import com.jacekgajek.artsplit.ops.Undo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.UpdateCost
import org.jetbrains.compose.resources.stringResource

/** How far back the event history looks; older changes are in the band-wide history. */
private const val MONTHS = 3

/** Changes to this event, newest first; loaded on demand. */
@Composable
fun HistorySection(context: EventContext, graph: AppGraph) {
    var shown by remember(context.event.id) { mutableStateOf(false) }
    var lines by remember(context.event.id) { mutableStateOf<List<HistoryLine>?>(null) }
    var failed by remember(context.event.id) { mutableStateOf(false) }
    var reload by remember(context.event.id) { mutableStateOf(0) }
    var confirming by remember { mutableStateOf<Undo?>(null) }
    val scope = rememberCoroutineScope()
    SectionTitle(stringResource(Res.string.history))
    if (!shown) {
        TextButton(onClick = { shown = true }) { Text(stringResource(Res.string.show_history)) }
        return
    }
    LaunchedEffect(context.event.id, reload) {
        runCatching {
            // Newest month first; each page is newest first. The first page of each month is enough for one event.
            (0 until MONTHS).flatMap { offset -> graph.api.history(context.band.id, historyMonth(offset)).entries }
        }.onSuccess { entries ->
            lines = entries.flatMap { it.toLines() }.filter { line ->
                line.envelope?.let { concernsEvent(it.op, context.event.id) } ?: (line.unreadableEventId == context.event.id)
            }
        }.onFailure { failed = true }
    }
    val loaded = lines
    Column {
        when {
            failed -> Text(stringResource(Res.string.history_unavailable), color = MaterialTheme.colorScheme.error)
            loaded == null -> CircularProgressIndicator(Modifier.size(24.dp))
            loaded.isEmpty() -> Text(stringResource(Res.string.history_empty))
            else -> {
                // The newest change of this event can be undone (VERSION_2 §7.2).
                val undo = undoOf(loaded.first(), context.can)
                loaded.forEachIndexed { index, line -> HistoryRow(context.band, line, onUndo = undo?.takeIf { index == 0 }?.let { { confirming = it } }) }
            }
        }
    }
    confirming?.let { undo ->
        UndoDialog(context.band, undo, onDismiss = { confirming = null }, onConfirm = {
            confirming = null
            if (context.actions.run(undo)) scope.launch { graph.sync.syncNow(); reload++ }
        })
    }
}

/** Operations on the event itself, plus costs added or moved to it. */
private fun concernsEvent(op: Operation, eventId: String): Boolean = when (op) {
    is Undo -> concernsEvent(op.original, eventId)
    is AddCost -> op.incurredForEventId == eventId
    is UpdateCost -> op.incurredForEventId?.value == eventId
    else -> op.eventId == eventId
}
