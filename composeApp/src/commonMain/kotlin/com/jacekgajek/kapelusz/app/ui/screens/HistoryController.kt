package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.rules.Capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the band history and an event's history share (ITERATION_3 R12): the loaded lines, this device's undos the
 * server does not have yet, and undo with a confirmation. The screens decide what to load.
 */
@Stable
class HistoryController(
    private val bands: BandRepository,
    private val sync: SyncEngine,
    private val bandId: Id,
    private val userId: String,
    private val memberId: String?,
    private val scope: CoroutineScope,
) {
    /** The server's lines, newest first; null while the first load runs. */
    var lines by mutableStateOf<List<HistoryLine>?>(null)
    var failed by mutableStateOf(false)

    /** Counts undos made here: the history loads again after each, quietly behind the line shown at once. */
    var reload by mutableStateOf(0)
        private set

    private var pending by mutableStateOf(listOf<HistoryLine>())
    private var confirming by mutableStateOf<Undo?>(null)

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

    /** Where the undo and redo arrows go among [shown] lines (ITERATION_2 §7.2); none when not [enabled]. */
    fun arrows(shown: List<HistoryLine>, can: Capabilities, enabled: Boolean = true): HistoryArrows {
        val targets = undoTargets(shown) { undoOf(it, can) != null }
        val undo = targets.undo?.takeIf { enabled }?.let { line -> undoOf(line, can)?.let { line to it } }
        val redo = targets.redo?.takeIf { enabled }?.let { line -> undoOf(line, can)?.let { line to it } }
        return HistoryArrows(targets.undone, undo, redo)
    }

    /** A history line with its arrows; a tap on one asks first. */
    @Composable
    fun LineRow(band: Band, line: HistoryLine, arrows: HistoryArrows) {
        HistoryRow(
            band,
            line,
            undone = line.opId in arrows.undone,
            onUndo = arrows.undo?.takeIf { it.first === line }?.let { { confirming = it.second } },
            onRedo = arrows.redo?.takeIf { it.first === line }?.let { { confirming = it.second } },
        )
    }

    /** The confirmation of a tapped arrow; the undo is shown at once, so undoing several changes is tap after tap. */
    @Composable
    fun UndoConfirmation(band: Band, actions: Actions) {
        val undo = confirming ?: return
        UndoDialog(band, undo, onDismiss = { confirming = null }, onConfirm = {
            confirming = null
            if (actions.run(undo)) {
                bands.lastOpId?.let { pending = pending + pendingUndoLine(undo, it, userId, memberId) }
                scope.launch {
                    sync.syncNow()
                    reload++
                }
            }
        })
    }
}

/** The arrows of a history list: the lines whose change is undone, and the line (with its undo) of each arrow. */
class HistoryArrows(val undone: Set<String>, val undo: Pair<HistoryLine, Undo>?, val redo: Pair<HistoryLine, Undo>?)
