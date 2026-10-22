package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.cancel
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

/** Asks before undoing, naming the change: the arrow is small, and an undo changes the band for everyone. */
@Composable
fun UndoDialog(band: Band, undo: Undo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(Res.string.undo_confirm, describe(undo.original, band))) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.undo)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
