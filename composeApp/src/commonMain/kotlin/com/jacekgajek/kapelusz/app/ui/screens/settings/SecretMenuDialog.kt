package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.copy_raw_data
import com.jacekgajek.kapelusz.app.generated.raw_data_copied
import com.jacekgajek.kapelusz.app.generated.raw_data_failed
import com.jacekgajek.kapelusz.app.generated.secret_menu
import com.jacekgajek.kapelusz.app.platform.LocalPlatform
import com.jacekgajek.kapelusz.app.ui.components.BusyButton
import com.jacekgajek.kapelusz.app.ui.components.ButtonKind
import com.jacekgajek.kapelusz.app.ui.components.rememberTask
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Tools for leaders: copy the band's raw data, e.g. to look into a problem. */
@Composable
internal fun SecretMenuDialog(rawData: suspend () -> String, onDismiss: () -> Unit) {
    val copy = LocalPlatform.current.clipboard.rememberCopy()
    val task = rememberTask()
    var result by remember { mutableStateOf<StringResource?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.secret_menu)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val copyRawData: suspend () -> Unit = {
                    result = runSuspendCatching { rawData() }.fold(
                        onSuccess = { if (copy(it)) Res.string.raw_data_copied else Res.string.raw_data_failed },
                        onFailure = { Res.string.raw_data_failed },
                    )
                }
                BusyButton(copyRawData, task = task, kind = ButtonKind.OUTLINED) {
                    if (!task.working) Icon(Icons.Default.ContentCopy, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(Res.string.copy_raw_data))
                }
                result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}
