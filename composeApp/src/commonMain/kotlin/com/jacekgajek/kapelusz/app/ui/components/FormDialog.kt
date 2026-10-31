package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import org.jetbrains.compose.resources.stringResource

/**
 * A dialog with a title, fields one under another, a [confirmButton] and Cancel. [dismissEnabled] off keeps it open
 * (no Cancel, no tap outside) while a call runs; [scroll] when the fields may not fit a phone's screen.
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    icon: @Composable (() -> Unit)? = null,
    dismissEnabled: Boolean = true,
    scroll: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        icon = icon,
        title = { Text(title) },
        text = {
            val scrolling = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(scrolling, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        },
        confirmButton = confirmButton,
        dismissButton = { TextButton(enabled = dismissEnabled, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
