package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.stuck_discard_text
import com.jacekgajek.kapelusz.app.generated.stuck_discard_title
import com.jacekgajek.kapelusz.app.generated.stuck_push_discard
import com.jacekgajek.kapelusz.app.generated.stuck_push_export
import com.jacekgajek.kapelusz.app.generated.stuck_push_export_failed
import com.jacekgajek.kapelusz.app.generated.stuck_push_exported
import com.jacekgajek.kapelusz.app.generated.stuck_push_title
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.sync.StuckPush
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The open band's unsent changes are refused by the server as a whole (ITERATION_3 R9): says so with the server's
 * message, and offers to copy them (as JSON) or to discard them.
 */
@Composable
fun StuckPushBanner(
    stuck: StuckPush,
    /** The unsent changes as JSON, read when exporting. */
    pendingJson: () -> String,
    /** Asks for discarding them; runs outside the banner, which leaves as soon as the band is no longer stuck. */
    onDiscard: () -> Unit,
    /** Shows a short confirmation, e.g. in a snackbar. */
    onMessage: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = LocalPlatform.current.clipboard.rememberCopy()
    val scope = rememberCoroutineScope()
    val exported = stringResource(Res.string.stuck_push_exported)
    val exportFailed = stringResource(Res.string.stuck_push_export_failed)
    var discarding by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(Res.string.stuck_push_title), style = MaterialTheme.typography.titleSmall)
                    if (stuck.message.isNotBlank()) Text(stuck.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { scope.launch { onMessage(if (copy(pendingJson())) exported else exportFailed) } }) {
                    Text(stringResource(Res.string.stuck_push_export))
                }
                TextButton(onClick = { discarding = true }) { Text(stringResource(Res.string.stuck_push_discard)) }
            }
        }
    }
    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text(stringResource(Res.string.stuck_discard_title)) },
            text = { Text(stringResource(Res.string.stuck_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    discarding = false
                    onDiscard()
                }) { Text(stringResource(Res.string.stuck_push_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
