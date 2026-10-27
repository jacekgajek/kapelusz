package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.jacekgajek.kapelusz.app.generated.delete_band
import com.jacekgajek.kapelusz.app.generated.leave_band_confirm
import com.jacekgajek.kapelusz.app.generated.leave_band_failed
import com.jacekgajek.kapelusz.app.generated.leave_band_owner_text
import com.jacekgajek.kapelusz.app.generated.leave_band_owner_title
import com.jacekgajek.kapelusz.app.generated.leave_band_return
import com.jacekgajek.kapelusz.app.generated.leave_band_text
import com.jacekgajek.kapelusz.app.generated.leave_band_title
import com.jacekgajek.kapelusz.app.generated.menu_roster
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Leaving the band: what it means, and the (red) button; the server unlinks the account, so it needs a connection. */
@Composable
internal fun LeaveBandDialog(bandName: String, onLeave: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.leave_band_title, bandName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.leave_band_text))
                Text(stringResource(Res.string.leave_band_return))
                if (failed) Text(stringResource(Res.string.leave_band_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    working = true
                    failed = false
                    scope.launch {
                        runSuspendCatching { onLeave() }.onFailure { failed = true }
                        working = false
                    }
                },
            ) {
                if (working) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                Text(stringResource(Res.string.leave_band_confirm))
            }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
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
        confirmButton = { TextButton(onClick = { onDismiss(); onOpenMembers() }) { Text(stringResource(Res.string.menu_roster)) } },
        dismissButton = {
            TextButton(onClick = onDeleteBand, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(Res.string.delete_band))
            }
        },
    )
}
