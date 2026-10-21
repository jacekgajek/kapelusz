package com.jacekgajek.artsplit.app.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.api.HistoryEntry
import com.jacekgajek.artsplit.app.sync.HistoryItem
import com.jacekgajek.artsplit.app.sync.ReadOp
import com.jacekgajek.artsplit.app.generated.history_newer_version
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.data.nowMillis
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.history
import com.jacekgajek.artsplit.app.generated.history_empty
import com.jacekgajek.artsplit.app.generated.history_unavailable
import com.jacekgajek.artsplit.app.generated.load_more
import com.jacekgajek.artsplit.app.generated.next_month
import com.jacekgajek.artsplit.app.generated.previous_month
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.model.describe
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMonth
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.ops.OpEnvelope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/** Calendar month as the API expects it, e.g. "2026-10"; [offset] months back from now (UTC). */
fun historyMonth(offset: Int): String {
    val now = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(TimeZone.UTC)
    val total = now.year * 12 + now.month.ordinal - offset
    return "${total / 12}-${(total % 12 + 1).toString().padStart(2, '0')}"
}

/** One history line: an operation with who made it and when; [envelope] is null for one this app cannot read. */
data class HistoryLine(val entry: HistoryEntry, val envelope: OpEnvelope?, val opId: String, val unreadableEventId: String? = null)

fun HistoryItem.toLines(): List<HistoryLine> = ops.map { op ->
    when (op) {
        is ReadOp.Known -> HistoryLine(entry, op.envelope, op.envelope.opId)
        is ReadOp.Unknown -> HistoryLine(entry, null, op.op.opId, op.op.eventId)
    }
}

/** Band-wide history, one month at a time, newest first. */
@Composable
fun HistoryScreen(graph: AppGraph, state: BandState.Ready, onBack: () -> Unit) {
    var offset by remember { mutableStateOf(0) }
    var lines by remember { mutableStateOf<List<HistoryLine>?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val month = historyMonth(offset)
    LaunchedEffect(state.band.id, month) {
        lines = null
        next = null
        failed = false
        runCatching { graph.api.history(state.band.id, month) }
            .onSuccess { response -> lines = response.entries.flatMap { it.toLines() }; next = response.next }
            .onFailure { failed = true }
    }
    val loadMore: () -> Unit = {
        scope.launch {
            loadingMore = true
            runCatching { graph.api.history(state.band.id, month, next) }
                .onSuccess { response -> lines = lines.orEmpty() + response.entries.flatMap { it.toLines() }; next = response.next }
                .onFailure { failed = true }
            loadingMore = false
        }
    }
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.history), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.previous_month)) }
                Text(formatMonth(month, language), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { offset-- }, enabled = offset > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.next_month))
                }
            }
            val padding16 = PaddingValues(16.dp)
            val loaded = lines
            when {
                failed -> Text(stringResource(Res.string.history_unavailable), Modifier.padding(padding16), color = MaterialTheme.colorScheme.error)
                loaded == null -> CircularProgressIndicator(Modifier.padding(padding16))
                loaded.isEmpty() -> Text(stringResource(Res.string.history_empty), Modifier.padding(padding16))
                else -> LazyColumn(contentPadding = padding16, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(loaded, key = { it.opId }) { line ->
                        HistoryRow(state.band, line)
                        HorizontalDivider()
                    }
                    if (next != null) {
                        item {
                            if (loadingMore) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
                            else TextButton(onClick = loadMore) { Text(stringResource(Res.string.load_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRow(band: Band, line: HistoryLine) {
    val who = line.entry.memberId?.let { band.member(it)?.name } ?: "?"
    val time = Instant.fromEpochMilliseconds(line.entry.at).toLocalDateTime(TimeZone.currentSystemDefault())
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(line.envelope?.let { describe(it.op, band) } ?: stringResource(Res.string.history_newer_version), style = MaterialTheme.typography.bodyMedium)
        Text(
            "$who · ${formatDate(time.date, language)} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
