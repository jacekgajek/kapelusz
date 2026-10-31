package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One call to the server (or another slow thing) a screen is waiting for: [working] while it runs, [failure] when it
 * threw. Runs one at a time: a second tap while [working] does nothing (two taps would make two invites).
 */
@Stable
class Task(private val scope: CoroutineScope) {
    var working by mutableStateOf(false)
        private set
    var failure by mutableStateOf<Throwable?>(null)
        private set
    val failed: Boolean get() = failure != null

    /** Starts [block] unless one is running; a failure is kept in [failure] until the next run or [clearFailure]. */
    fun run(block: suspend () -> Unit) {
        if (working) return
        working = true
        failure = null
        scope.launch {
            try {
                runSuspendCatching { block() }.onFailure { failure = it }
            } finally {
                working = false
            }
        }
    }

    fun clearFailure() {
        failure = null
    }
}

@Composable
fun rememberTask(): Task {
    val scope = rememberCoroutineScope()
    return remember { Task(scope) }
}

/** How a [BusyButton] looks: the Material button kinds. */
enum class ButtonKind { FILLED, TONAL, OUTLINED, TEXT }

/**
 * A button that runs [onClick] as a [task]: disabled with a spinner in front of its [content] while it works. Share
 * the [task] with the dialog around it when that has to know too (e.g. to stay open while the call runs).
 */
@Composable
fun BusyButton(
    onClick: suspend () -> Unit,
    modifier: Modifier = Modifier,
    task: Task = rememberTask(),
    enabled: Boolean = true,
    kind: ButtonKind = ButtonKind.FILLED,
    colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val busyContent: @Composable RowScope.() -> Unit = {
        if (task.working) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        }
        content()
    }
    val isEnabled = enabled && !task.working
    val click = { task.run(onClick) }
    when (kind) {
        ButtonKind.FILLED -> {
            Button(click, modifier, isEnabled, colors = colors ?: ButtonDefaults.buttonColors(), content = busyContent)
        }

        ButtonKind.TONAL -> {
            val tonal = colors ?: ButtonDefaults.filledTonalButtonColors()
            FilledTonalButton(click, modifier, isEnabled, colors = tonal, content = busyContent)
        }

        ButtonKind.OUTLINED -> {
            OutlinedButton(click, modifier, isEnabled, colors = colors ?: ButtonDefaults.outlinedButtonColors(), content = busyContent)
        }

        ButtonKind.TEXT -> {
            TextButton(click, modifier, isEnabled, colors = colors ?: ButtonDefaults.textButtonColors(), content = busyContent)
        }
    }
}

/** A filled button's colours for something destructive (delete the band, leave it): the error colours. */
@Composable
fun dangerButtonColors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
}
