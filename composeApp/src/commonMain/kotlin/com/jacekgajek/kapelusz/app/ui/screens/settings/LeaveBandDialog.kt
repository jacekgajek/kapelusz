package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.delete_band
import com.jacekgajek.kapelusz.app.generated.leave_band_confirm
import com.jacekgajek.kapelusz.app.generated.leave_band_failed
import com.jacekgajek.kapelusz.app.generated.leave_band_owner_text
import com.jacekgajek.kapelusz.app.generated.leave_band_owner_title
import com.jacekgajek.kapelusz.app.generated.leave_band_return
import com.jacekgajek.kapelusz.app.generated.leave_band_stuck
import com.jacekgajek.kapelusz.app.generated.leave_band_text
import com.jacekgajek.kapelusz.app.generated.leave_band_title
import com.jacekgajek.kapelusz.app.generated.menu_roster
import com.jacekgajek.kapelusz.app.sync.LeaveBlocked
import com.jacekgajek.kapelusz.app.ui.components.BusyButton
import com.jacekgajek.kapelusz.app.ui.components.FormDialog
import com.jacekgajek.kapelusz.app.ui.components.dangerButtonColors
import com.jacekgajek.kapelusz.app.ui.components.rememberTask
import org.jetbrains.compose.resources.stringResource

/**
 * Leaving the band: what it means, what else it ends ([warnings]: a pair settling together, the band fund held), and the
 * (red) button; the server unlinks the account, so it needs a connection.
 */
@Composable
internal fun LeaveBandDialog(bandName: String, warnings: List<String>, onLeave: suspend () -> Unit, onDismiss: () -> Unit) {
    val task = rememberTask()
    FormDialog(
        title = stringResource(Res.string.leave_band_title, bandName),
        onDismiss = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
        dismissEnabled = !task.working,
        scroll = true,
        confirmButton = {
            BusyButton(onLeave, task = task, colors = dangerButtonColors()) { Text(stringResource(Res.string.leave_band_confirm)) }
        },
    ) {
        Text(stringResource(Res.string.leave_band_text))
        warnings.forEach { Text(it) }
        Text(stringResource(Res.string.leave_band_return))
        task.failure?.let { e ->
            val text = if ((e as? LeaveBlocked)?.stuck == true) Res.string.leave_band_stuck else Res.string.leave_band_failed
            Text(stringResource(text), color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The owner can't leave: the band would have nobody to own it. Either hand it over in Band members ([onOpenMembers])
 * or delete it ([onDeleteBand] closes the dialog onto the Delete band button, just below).
 */
@Composable
internal fun OwnerCannotLeaveDialog(onOpenMembers: () -> Unit, onDeleteBand: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
        title = { Text(stringResource(Res.string.leave_band_owner_title)) },
        text = { Text(stringResource(Res.string.leave_band_owner_text)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenMembers()
            }) { Text(stringResource(Res.string.menu_roster)) }
        },
        dismissButton = {
            TextButton(onClick = onDeleteBand, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(Res.string.delete_band))
            }
        },
    )
}
