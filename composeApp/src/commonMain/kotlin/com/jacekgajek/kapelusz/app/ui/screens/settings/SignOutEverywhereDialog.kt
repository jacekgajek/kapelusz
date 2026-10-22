package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere_failed
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere_text
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "Sign out on all devices" (ITERATION_3 R6), confirmed first: it needs the server, so offline it says so and this
 * device stays signed in.
 */
@Composable
internal fun SignOutEverywhereDialog(onSignOutEverywhere: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(Res.string.sign_out_everywhere)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.sign_out_everywhere_text))
                if (failed) Text(stringResource(Res.string.sign_out_everywhere_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    working = true
                    failed = false
                    scope.launch {
                        runSuspendCatching { onSignOutEverywhere() }.onFailure { failed = true }
                        working = false
                    }
                },
            ) {
                if (working) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.sign_out_everywhere))
            }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
