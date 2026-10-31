package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere_failed
import com.jacekgajek.kapelusz.app.generated.sign_out_everywhere_text
import com.jacekgajek.kapelusz.app.ui.components.BusyButton
import com.jacekgajek.kapelusz.app.ui.components.ButtonKind
import com.jacekgajek.kapelusz.app.ui.components.FormDialog
import com.jacekgajek.kapelusz.app.ui.components.rememberTask
import org.jetbrains.compose.resources.stringResource

/**
 * "Sign out on all devices" (ITERATION_3 R6), confirmed first: it needs the server, so offline it says so and this
 * device stays signed in.
 */
@Composable
internal fun SignOutEverywhereDialog(onSignOutEverywhere: suspend () -> Unit, onDismiss: () -> Unit) {
    val task = rememberTask()
    FormDialog(
        title = stringResource(Res.string.sign_out_everywhere),
        onDismiss = onDismiss,
        dismissEnabled = !task.working,
        confirmButton = {
            BusyButton(onSignOutEverywhere, task = task, kind = ButtonKind.TEXT) { Text(stringResource(Res.string.sign_out_everywhere)) }
        },
    ) {
        Text(stringResource(Res.string.sign_out_everywhere_text))
        if (task.failed) Text(stringResource(Res.string.sign_out_everywhere_failed), color = MaterialTheme.colorScheme.error)
    }
}
