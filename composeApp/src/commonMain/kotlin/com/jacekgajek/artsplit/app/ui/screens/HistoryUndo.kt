package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.redo
import com.jacekgajek.artsplit.app.generated.redo_confirm
import com.jacekgajek.artsplit.app.generated.undo
import com.jacekgajek.artsplit.app.generated.undo_confirm
import com.jacekgajek.artsplit.app.ui.model.Can
import com.jacekgajek.artsplit.app.ui.model.describe
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.ops.Undo
import com.jacekgajek.artsplit.ops.isUndoable
import org.jetbrains.compose.resources.stringResource

/**
 * The undo of [line] (VERSION_2 §7.2), or null when it cannot be undone: a kind of change that never can, one from
 * before changes were recorded, one that would bring back deleted files, or one [can] does not allow.
 */
fun undoOf(line: HistoryLine, can: Can): Undo? {
    val envelope = line.envelope ?: return null
    val change = line.change ?: return null
    if (!isUndoable(envelope.op) || change.isEmpty || change.restoresAttachments) return null
    return Undo(envelope.opId, envelope.op, change).takeIf { can.allowed(it) }
}

/**
 * Where the arrows go in a history list (VERSION_2 §7.2), [lines] newest first: undo on the newest change that is
 * not undone, so tapping again steps further back; redo on the newest undo still in effect, unless a change was made
 * after it. [undone] are the lines whose change is currently undone.
 */
class UndoTargets(val undo: HistoryLine?, val redo: HistoryLine?, val undone: Set<String>)

fun undoTargets(lines: List<HistoryLine>): UndoTargets {
    // Newest first: an undo in effect cancels its target; an undo that was itself undone (redone) cancels nothing.
    val undone = mutableSetOf<String>()
    for (line in lines) {
        if (line.opId in undone) continue
        (line.envelope?.op as? Undo)?.let { undone += it.undoneOpId }
    }
    val active = lines.filter { it.opId !in undone }
    val undo = active.firstOrNull { it.envelope?.op !is Undo }
    val redo = active.firstOrNull { (it.envelope?.op as? Undo)?.original !is Undo }?.takeIf { it.envelope?.op is Undo }
    return UndoTargets(undo, redo, undone)
}

/** Asks before undoing, naming the change: the arrow is small, and an undo changes the band for everyone. */
@Composable
fun UndoDialog(band: Band, undo: Undo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // A redo is the undo of an undo; it is asked as redoing the original change.
        text = {
            val original = undo.original
            Text(
                if (original is Undo) stringResource(Res.string.redo_confirm, describe(original.original, band))
                else stringResource(Res.string.undo_confirm, describe(original, band)),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(if (undo.original is Undo) Res.string.redo else Res.string.undo)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
