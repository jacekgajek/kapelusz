package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.delete_band_confirm
import com.jacekgajek.kapelusz.app.generated.delete_band_everything
import com.jacekgajek.kapelusz.app.generated.delete_band_failed
import com.jacekgajek.kapelusz.app.generated.delete_band_final
import com.jacekgajek.kapelusz.app.generated.delete_band_members
import com.jacekgajek.kapelusz.app.generated.delete_band_no_members
import com.jacekgajek.kapelusz.app.generated.delete_band_title
import com.jacekgajek.kapelusz.app.generated.delete_band_type_name
import com.jacekgajek.kapelusz.app.generated.delete_band_unsynced
import com.jacekgajek.kapelusz.app.ui.model.label
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The last step before deleting a band for everyone: every consequence spelled out, and the band's name typed out to
 * enable the (red) button, so it cannot happen by a stray tap.
 */
@Composable
internal fun DeleteBandDialog(bandName: String, otherMembers: Int, onDelete: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val matches = typed.trim().equals(bandName.trim(), ignoreCase = true)
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.delete_band_title, bandName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.delete_band_everything))
                Text(
                    if (otherMembers > 0) {
                        pluralStringResource(Res.plurals.delete_band_members, otherMembers, otherMembers)
                    } else {
                        stringResource(Res.string.delete_band_no_members)
                    },
                )
                Text(stringResource(Res.string.delete_band_unsynced))
                Text(stringResource(Res.string.delete_band_final), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    typed,
                    {
                        typed = it
                        failed = false
                    },
                    label = { Text(stringResource(Res.string.delete_band_type_name, bandName)) },
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (failed) Text(stringResource(Res.string.delete_band_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = matches && !working,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    working = true
                    scope.launch {
                        runSuspendCatching { onDelete() }.onFailure { failed = true }
                        working = false
                    }
                },
            ) {
                if (working) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                Text(stringResource(Res.string.delete_band_confirm))
            }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
