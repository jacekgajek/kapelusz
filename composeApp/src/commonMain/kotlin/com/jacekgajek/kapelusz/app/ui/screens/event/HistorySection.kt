package com.jacekgajek.kapelusz.app.ui.screens.event

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import androidx.compose.runtime.rememberCoroutineScope
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.app.ui.components.CollapsibleHeader
import com.jacekgajek.kapelusz.app.ui.components.SectionState
import com.jacekgajek.kapelusz.app.ui.screens.HistoryController
import androidx.compose.runtime.setValue
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
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.history
import com.jacekgajek.kapelusz.app.generated.history_empty
import com.jacekgajek.kapelusz.app.generated.history_unavailable
import com.jacekgajek.kapelusz.app.ui.screens.historyMonth
import com.jacekgajek.kapelusz.app.ui.screens.toLines
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UpdateCost
import org.jetbrains.compose.resources.stringResource

/** How far back the event history looks; older changes are in the band-wide history. */
private const val MONTHS = 3

/** Changes to this event, newest first; loaded on demand. */
@Composable
fun HistorySection(context: EventContext, graph: AppGraph, section: SectionState) {
    val scope = rememberCoroutineScope()
    val history = remember(context.event.id) { HistoryController(graph, context.band.id, context.me?.userId.orEmpty(), context.me?.id, scope) }
    // Loaded only when opened: it asks the server.
    CollapsibleHeader(stringResource(Res.string.history), section)
    if (!section.expanded) return
    LaunchedEffect(context.event.id, history.reload) {
        history.load {
            // Newest month first; each page is newest first. The first page of each month is enough for one event.
            // The months load at the same time.
            val entries = coroutineScope {
                (0 until MONTHS).map { offset -> async { graph.api.history(context.band.id, historyMonth(offset)).entries } }.awaitAll().flatten()
            }
            entries.flatMap { it.toLines() }.filter { line ->
                line.envelope?.let { concernsEvent(it.op, context.event.id) } ?: (line.unreadableEventId == context.event.id)
            }
        }
    }
    val loaded = history.shown()
    Column {
        when {
            history.failed -> Text(stringResource(Res.string.history_unavailable), color = MaterialTheme.colorScheme.error)
            loaded == null -> CircularProgressIndicator(Modifier.size(24.dp))
            loaded.isEmpty() -> Text(stringResource(Res.string.history_empty))
            else -> {
                // Undo steps back through this event's changes, redo forward (ITERATION_2 §7.2).
                val arrows = history.arrows(loaded, context.can)
                loaded.forEach { line -> history.LineRow(context.band, line, arrows) }
            }
        }
    }
    history.UndoConfirmation(context.band, context.actions)
}

/** Operations on the event itself, plus costs added or moved to it. */
private fun concernsEvent(op: Operation, eventId: String): Boolean = when (op) {
    is Undo -> concernsEvent(op.original, eventId)
    is AddCost -> op.incurredForEventId == eventId
    is UpdateCost -> op.incurredForEventId?.value == eventId
    else -> op.eventId == eventId
}
