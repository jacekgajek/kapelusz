package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.sync.HistoryPage
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.rules.Capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the band history and an event's history share (I3 R12): the loaded lines, this device's undos the
 * server does not have yet, and the undo waiting for its confirmation. The screens decide what to load and draw it.
 */
@Stable
class HistoryState(
    private val bands: BandRepository,
    private val syncNow: suspend () -> Unit,
    private val bandId: Id,
    private val userId: String,
    private val memberId: String?,
    private val scope: CoroutineScope,
) {
    /** The server's lines, newest first; null while the first load runs. */
    var lines by mutableStateOf<List<HistoryLine>?>(null)
    var failed by mutableStateOf(false)

    /** Counts undos made here: the history loads again after each, quietly behind the line shown at once. */
    var reload by mutableIntStateOf(0)
        private set

    private var pending by mutableStateOf(listOf<HistoryLine>())

    /** The undo (or redo) tapped, waiting for the user to confirm it. */
    var confirming by mutableStateOf<Undo?>(null)
        private set

    /** Loads the lines with [fetch]. A load replaced by a newer one (cancelled) is not a failure. */
    suspend fun load(fetch: suspend () -> List<HistoryLine>) {
        runSuspendCatching { fetch() }
            .onSuccess {
                lines = it
                failed = false
                pending = stillPending(pending, bands.local(bandId)?.pending.orEmpty().map { op -> op.opId }.toSet())
            }
            .onFailure { failed = true }
    }

    /** The lines to show: the server's, with this device's undos it does not have yet on top (when [withPending]). */
    fun shown(withPending: Boolean = true): List<HistoryLine>? = lines?.let { withPending(it, if (withPending) pending else emptyList()) }

    /** Where the undo and redo arrows go among [shown] lines (I2 §7.2); none when not [enabled]. */
    fun arrows(shown: List<HistoryLine>, can: Capabilities, enabled: Boolean = true): HistoryArrows {
        val targets = undoTargets(shown) { undoOf(it, can) != null }
        val undo = targets.undo?.takeIf { enabled }?.let { line -> undoOf(line, can)?.let { line to it } }
        val redo = targets.redo?.takeIf { enabled }?.let { line -> undoOf(line, can)?.let { line to it } }
        return HistoryArrows(targets.undone, undo, redo)
    }

    fun ask(undo: Undo) {
        confirming = undo
    }

    fun dismiss() {
        confirming = null
    }

    /** The confirmed undo went through: shown at once as pending, synced, and the history loaded again behind it. */
    fun undone(undo: Undo) {
        confirming = null
        bands.lastOpId?.let { pending = pending + pendingUndoLine(undo, it, userId, memberId) }
        scope.launch {
            syncNow()
            reload++
        }
    }
}

/** The arrows of a history list: the lines whose change is undone, and the line (with its undo) of each arrow. */
class HistoryArrows(val undone: Set<String>, val undo: Pair<HistoryLine, Undo>?, val redo: Pair<HistoryLine, Undo>?)

/**
 * The band history screen (I2 §7): a month at a time, newest first, more of it on demand; this device's
 * undos on top of the current month. Scoped to the screen's back-stack entry.
 */
class HistoryViewModel(
    bands: BandRepository,
    syncNow: suspend () -> Unit,
    private val bandId: Id,
    userId: String,
    memberId: String?,
    private val fetch: suspend (month: String, next: String?) -> HistoryPage,
) : ViewModel() {
    val history = HistoryState(bands, syncNow, bandId, userId, memberId, viewModelScope)

    /** Months back from the current one. */
    var offset by mutableIntStateOf(0)
        private set
    val month: String get() = historyMonth(offset)

    /** The server's cursor for the rest of the month, if any. */
    var next by mutableStateOf<String?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var loadMoreFailed by mutableStateOf(false)
        private set
    private var linesMonth by mutableStateOf<String?>(null)

    fun earlier() {
        offset++
    }

    fun later() {
        if (offset > 0) offset--
    }

    /** The month's first page: another month starts from a spinner; the same one refreshes in place (e.g. after an undo). */
    suspend fun loadMonth() {
        val forMonth = month
        if (linesMonth != forMonth) {
            history.lines = null
            next = null
        }
        history.failed = false
        history.load {
            val response = fetch(forMonth, null)
            next = response.next
            linesMonth = forMonth
            response.entries.flatMap { it.toLines() }
        }
    }

    /** The next page of the month; what is shown stays, only "Load more" can fail (I3 R8). */
    fun loadMore() {
        loadMoreFailed = false
        viewModelScope.launch {
            loadingMore = true
            val refresh = history.reload
            val forMonth = month
            runSuspendCatching { fetch(forMonth, next) }
                // Not onto a list that was refreshed or that shows another month meanwhile.
                .onSuccess { response ->
                    if (refresh == history.reload && forMonth == month) {
                        history.lines = history.lines.orEmpty() + response.entries.flatMap { it.toLines() }
                        next = response.next
                    }
                }
                .onFailure { loadMoreFailed = true }
            loadingMore = false
        }
    }
}
