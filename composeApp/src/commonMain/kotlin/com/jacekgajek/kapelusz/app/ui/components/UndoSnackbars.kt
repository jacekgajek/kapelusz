package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.undo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** How long an Undo snackbar shows (ITERATION_5 §2). */
const val UNDO_TIME = 10_000L

/** One Undo snackbar: [message]; [undo] takes the change back; [gone] lets it sync. */
class UndoItem(val message: String, val undo: () -> Unit, val gone: () -> Unit)

/**
 * Undo snackbars shown together, each with its own time (ITERATION_5 §2): at most [MAX_UNDOS]; a new one pushes out
 * the oldest, whose change then syncs.
 */
@Stable
class UndoStack {
    val items = mutableStateListOf<UndoItem>()

    fun add(item: UndoItem) {
        items += item
        while (items.size > MAX_UNDOS) dismiss(items.first())
    }

    fun dismiss(item: UndoItem) {
        if (items.remove(item)) item.gone()
    }

    fun undo(item: UndoItem) {
        if (items.remove(item)) item.undo()
    }

    fun dismissAll() = items.toList().forEach(::dismiss)

    companion object {
        const val MAX_UNDOS = 3
    }
}

/** The [stack]'s snackbars, oldest on top; goes in a screen's snackbar slot, above its usual [SnackbarHost]. */
@Composable
fun UndoSnackbars(stack: UndoStack) {
    val undoLabel = stringResource(Res.string.undo)
    val closeLabel = stringResource(Res.string.close)
    // Leaving the screen, or the app, ends every hold: Undo is offered only while it can be seen.
    DisposableEffect(stack) { onDispose { stack.dismissAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stack.dismissAll() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        stack.items.forEach { item ->
            key(item) {
                LaunchedEffect(item) {
                    delay(UNDO_TIME)
                    stack.dismiss(item)
                }
                Snackbar(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    action = { TextButton(onClick = { stack.undo(item) }) { Text(undoLabel) } },
                    dismissAction = { IconButton(onClick = { stack.dismiss(item) }) { Icon(Icons.Default.Close, closeLabel) } },
                ) { Text(item.message) }
            }
        }
    }
}
