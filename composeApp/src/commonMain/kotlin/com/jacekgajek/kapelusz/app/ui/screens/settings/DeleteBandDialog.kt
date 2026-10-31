package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.delete_band_confirm
import com.jacekgajek.kapelusz.app.generated.delete_band_everything
import com.jacekgajek.kapelusz.app.generated.delete_band_failed
import com.jacekgajek.kapelusz.app.generated.delete_band_final
import com.jacekgajek.kapelusz.app.generated.delete_band_members
import com.jacekgajek.kapelusz.app.generated.delete_band_no_members
import com.jacekgajek.kapelusz.app.generated.delete_band_title
import com.jacekgajek.kapelusz.app.generated.delete_band_type_name
import com.jacekgajek.kapelusz.app.generated.delete_band_unsynced
import com.jacekgajek.kapelusz.app.ui.components.BusyButton
import com.jacekgajek.kapelusz.app.ui.components.FormDialog
import com.jacekgajek.kapelusz.app.ui.components.dangerButtonColors
import com.jacekgajek.kapelusz.app.ui.components.rememberTask
import com.jacekgajek.kapelusz.app.ui.text.label
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The last step before deleting a band for everyone: every consequence spelled out, and the band's name typed out to
 * enable the (red) button, so it cannot happen by a stray tap.
 */
@Composable
internal fun DeleteBandDialog(bandName: String, otherMembers: Int, onDelete: suspend () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val task = rememberTask()
    val matches = typed.trim().equals(bandName.trim(), ignoreCase = true)
    FormDialog(
        title = stringResource(Res.string.delete_band_title, bandName),
        onDismiss = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        dismissEnabled = !task.working,
        scroll = true,
        confirmButton = {
            BusyButton(onDelete, task = task, enabled = matches, colors = dangerButtonColors()) {
                Text(stringResource(Res.string.delete_band_confirm))
            }
        },
    ) {
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
                task.clearFailure()
            },
            label = { Text(stringResource(Res.string.delete_band_type_name, bandName)) },
            singleLine = true,
            enabled = !task.working,
            modifier = Modifier.fillMaxWidth(),
        )
        if (task.failed) Text(stringResource(Res.string.delete_band_failed), color = MaterialTheme.colorScheme.error)
    }
}
