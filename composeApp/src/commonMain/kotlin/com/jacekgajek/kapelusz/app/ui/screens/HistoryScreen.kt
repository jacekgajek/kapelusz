package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.history
import com.jacekgajek.kapelusz.app.generated.history_empty
import com.jacekgajek.kapelusz.app.generated.history_newer_version
import com.jacekgajek.kapelusz.app.generated.history_unavailable
import com.jacekgajek.kapelusz.app.generated.history_undone
import com.jacekgajek.kapelusz.app.generated.load_more
import com.jacekgajek.kapelusz.app.generated.next_month
import com.jacekgajek.kapelusz.app.generated.previous_month
import com.jacekgajek.kapelusz.app.generated.redo
import com.jacekgajek.kapelusz.app.generated.undo
import com.jacekgajek.kapelusz.app.sync.HistoryItem
import com.jacekgajek.kapelusz.app.sync.ReadOp
import com.jacekgajek.kapelusz.app.ui.BackNav
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.app.ui.format.formatMonth
import com.jacekgajek.kapelusz.app.ui.text.describe
import com.jacekgajek.kapelusz.app.util.nowMillis
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** Calendar month as the API expects it, e.g. "2026-10"; [offset] months back from now (UTC). */
fun historyMonth(offset: Int): String {
    val now = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(TimeZone.UTC)
    val total = now.year * 12 + now.month.ordinal - offset
    return "${total / 12}-${(total % 12 + 1).toString().padStart(2, '0')}"
}

/** One history line: an operation with who made it and when; [envelope] is null for one this app cannot read. */
data class HistoryLine(val entry: HistoryEntry, val envelope: OpEnvelope?, val opId: String, val unreadableEventId: String? = null) {
    /** What the operation changed, for undo; null for changes from before V2-M7. */
    val change: BandChange? get() = envelope?.let { entry.changes[it.opId] }
}

/** The entry's operations as lines, newest first like the entries themselves. */
fun HistoryItem.toLines(): List<HistoryLine> = ops.asReversed().map { op ->
    when (op) {
        is ReadOp.Known -> HistoryLine(entry, op.envelope, op.envelope.opId)
        is ReadOp.Unknown -> HistoryLine(entry, null, op.op.opId, op.op.eventId)
    }
}

/** Band-wide history, one month at a time, newest first. */
@Composable
fun HistoryScreen(session: BandSession, nav: BackNav, screen: HistoryViewModel) {
    val state = session.state
    val changes = session.changes
    val onBack: () -> Unit = nav::back
    val messages = rememberMessages()
    val actions = rememberActions(changes, messages, undos = null)
    val history = screen.history
    val month = screen.month
    val offset = screen.offset
    LaunchedEffect(state.band.id, month, history.reload) { screen.loadMonth() }
    UndoConfirmation(history, state.band, actions)
    AppScaffold(messages, topBar = { BackTopBar(stringResource(Res.string.history), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).listWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = screen::earlier) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.previous_month))
                }
                Text(formatMonth(month, language), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = screen::later, enabled = offset > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.next_month))
                }
            }
            val padding16 = PaddingValues(16.dp)
            val loaded = history.shown(withPending = offset == 0)
            when {
                history.failed -> Text(stringResource(Res.string.history_unavailable), Modifier.padding(padding16), color = MaterialTheme.colorScheme.error)

                loaded == null -> CircularProgressIndicator(Modifier.padding(padding16))

                loaded.isEmpty() -> Text(stringResource(Res.string.history_empty), Modifier.padding(padding16))

                else -> LazyColumn(contentPadding = padding16, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Undo steps back from the newest change, redo forward (ITERATION_2 §7.2); both only from this month.
                    val arrows = history.arrows(loaded, session.can, enabled = offset == 0)
                    items(loaded, key = { it.opId }) { line ->
                        HistoryLineRow(history, state.band, line, arrows)
                        HorizontalDivider()
                    }
                    if (screen.next != null) {
                        item {
                            if (screen.loadingMore) {
                                CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
                            } else {
                                Column {
                                    if (screen.loadMoreFailed) {
                                        val unavailable = stringResource(Res.string.history_unavailable)
                                        val error = MaterialTheme.colorScheme.error
                                        Text(unavailable, color = error, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = screen::loadMore) { Text(stringResource(Res.string.load_more)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A history line with its arrows; a tap on one asks first. */
@Composable
fun HistoryLineRow(history: HistoryState, band: Band, line: HistoryLine, arrows: HistoryArrows) {
    HistoryRow(
        band,
        line,
        undone = line.opId in arrows.undone,
        onUndo = arrows.undo?.takeIf { it.first === line }?.let { { history.ask(it.second) } },
        onRedo = arrows.redo?.takeIf { it.first === line }?.let { { history.ask(it.second) } },
    )
}

/** The confirmation of a tapped arrow; the undo is shown at once, so undoing several changes is tap after tap. */
@Composable
fun UndoConfirmation(history: HistoryState, band: Band, actions: Actions) {
    val undo = history.confirming ?: return
    UndoDialog(band, undo, onDismiss = history::dismiss, onConfirm = { if (actions.run(undo)) history.undone(undo) else history.dismiss() })
}

@Composable
fun HistoryRow(band: Band, line: HistoryLine, undone: Boolean = false, onUndo: (() -> Unit)? = null, onRedo: (() -> Unit)? = null) {
    val who = line.entry.memberId?.let { band.member(it)?.name } ?: "?"
    val time = Instant.fromEpochMilliseconds(line.entry.at).toLocalDateTime(TimeZone.currentSystemDefault())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HistoryText(band, line, who, time, undone, Modifier.weight(1f))
        onUndo?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(Res.string.undo)) } }
        onRedo?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(Res.string.redo)) } }
    }
}

@Composable
private fun HistoryText(band: Band, line: HistoryLine, who: String, time: LocalDateTime, undone: Boolean, modifier: Modifier) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(
            line.envelope?.let { describe(it.op, band, line.change, by = line.entry.memberId) } ?: stringResource(Res.string.history_newer_version),
            style = MaterialTheme.typography.bodyMedium,
            // A change that is currently undone (ITERATION_2 §7.2).
            textDecoration = if (undone) TextDecoration.LineThrough else null,
        )
        Text(
            "$who · ${formatDate(time.date, language)} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}" +
                if (undone) " · " + stringResource(Res.string.history_undone) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
