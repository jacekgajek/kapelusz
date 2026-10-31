package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.bands.Changes
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.undo_too_late
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.ops.Operation

/** Applies operations; a rejection is told through [messages]. */
class Actions(private val changes: Changes, val messages: Messages, private val undos: UndoStack? = messages.undos) {
    fun run(op: Operation): Boolean = show(changes.dispatch(op))

    /** Tells [rejection] (if any); true when there was none. */
    fun show(rejection: Rejection?): Boolean {
        if (rejection == null) return true
        messages.post(UiMessage.Rejected(rejection.reason))
        return false
    }

    fun say(message: UiMessage) = messages.post(message)

    fun say(text: String) = messages.post(text)

    /**
     * Runs [op] with Undo in a snackbar saying [message] (ITERATION_5 §2): it is held back from syncing while the
     * snackbar shows, and Undo takes it back as if it was never made.
     */
    fun runWithUndo(op: Operation, message: UiMessage): Boolean {
        val stack = undos ?: return run(op)
        return show(
            changes.dispatchHeld(op, stack.timeout + HOLD_MARGIN) { held ->
                val undo = {
                    // Its hold ran out first (e.g. the device slept): it may be on its way, so only the history can undo it.
                    if (!changes.withdraw(held)) {
                        changes.release(held)
                        messages.post(UiMessage.Text(Res.string.undo_too_late))
                    }
                }
                stack.add(UndoItem(message, undo = undo, gone = { changes.release(held) }))
            },
        )
    }

    fun runWithUndo(op: Operation, message: String): Boolean = runWithUndo(op, UiMessage.Plain(message))

    companion object {
        /**
         * A hold lasts this much longer than its snackbar shows: the snackbar ends the hold itself; the hold's own end
         * is for when the app is closed meanwhile.
         */
        const val HOLD_MARGIN = 5_000L
    }
}

/** Inside a dialog: what [actions] say while it is open, shown here rather than in the snackbar behind it. */
@Composable
fun DialogNotice(actions: Actions) = DialogNotice(actions.messages)

/** The screen's [Actions]: changes told through its [Messages]; without Undo snackbars when [undos] is null. */
@Composable
fun rememberActions(changes: Changes, messages: Messages, undos: UndoStack? = messages.undos): Actions = remember(changes, messages, undos) { Actions(changes, messages, undos) }
