package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.updates_allow
import com.jacekgajek.kapelusz.app.generated.updates_auto
import com.jacekgajek.kapelusz.app.generated.updates_check_now
import com.jacekgajek.kapelusz.app.generated.updates_checking
import com.jacekgajek.kapelusz.app.generated.updates_declined
import com.jacekgajek.kapelusz.app.generated.updates_downloading
import com.jacekgajek.kapelusz.app.generated.updates_failed
import com.jacekgajek.kapelusz.app.generated.updates_install
import com.jacekgajek.kapelusz.app.generated.updates_install_failed
import com.jacekgajek.kapelusz.app.generated.updates_install_now
import com.jacekgajek.kapelusz.app.generated.updates_installing
import com.jacekgajek.kapelusz.app.generated.updates_needs_confirmation
import com.jacekgajek.kapelusz.app.generated.updates_needs_permission
import com.jacekgajek.kapelusz.app.generated.updates_ready
import com.jacekgajek.kapelusz.app.generated.updates_title
import com.jacekgajek.kapelusz.app.generated.updates_unavailable
import com.jacekgajek.kapelusz.app.generated.updates_up_to_date
import com.jacekgajek.kapelusz.app.platform.UpdateControls
import com.jacekgajek.kapelusz.app.platform.UpdateStatus
import com.jacekgajek.kapelusz.app.ui.model.label
import org.jetbrains.compose.resources.stringResource

/** Android: automatic updates (ITERATION_2 §1): the switch, where the update stands, and what can be done about it. */
@Composable
internal fun AppUpdatesSection(controls: UpdateControls, autoUpdate: Boolean, onAutoUpdate: (Boolean) -> Unit) {
    val status by controls.status.collectAsState()
    SectionHeader(stringResource(Res.string.updates_title))
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = autoUpdate, role = Role.Switch, onValueChange = onAutoUpdate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.updates_auto), Modifier.weight(1f))
        Switch(checked = autoUpdate, onCheckedChange = null)
    }
    val (message, action) = when (val s = status) {
        UpdateStatus.Idle -> null to null
        UpdateStatus.Checking -> stringResource(Res.string.updates_checking) to null
        UpdateStatus.UpToDate -> stringResource(Res.string.updates_up_to_date) to null
        is UpdateStatus.Downloading -> stringResource(Res.string.updates_downloading, s.versionName) to null
        is UpdateStatus.Ready -> stringResource(Res.string.updates_ready, s.versionName) to (Res.string.updates_install_now to controls::installNow)
        is UpdateStatus.NeedsInstallPermission -> stringResource(Res.string.updates_needs_permission, s.versionName) to (Res.string.updates_allow to controls::openInstallPermission)
        is UpdateStatus.NeedsConfirmation -> stringResource(Res.string.updates_needs_confirmation, s.versionName) to (Res.string.updates_install to controls::confirm)
        is UpdateStatus.Installing -> stringResource(Res.string.updates_installing, s.versionName) to null
        is UpdateStatus.Declined -> stringResource(Res.string.updates_declined, s.versionName) to (Res.string.updates_install_now to controls::installNow)
        is UpdateStatus.InstallFailed -> stringResource(Res.string.updates_install_failed, s.versionName) to (Res.string.updates_install_now to controls::installNow)
        UpdateStatus.Failed -> stringResource(Res.string.updates_failed) to null
        UpdateStatus.Unavailable -> stringResource(Res.string.updates_unavailable) to null
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        action?.let { (label, onClick) -> Button(onClick = onClick) { Text(stringResource(label)) } }
        if (status != UpdateStatus.Unavailable) {
            OutlinedButton(onClick = controls::checkNow, enabled = status !is UpdateStatus.Checking && status !is UpdateStatus.Downloading) {
                Text(stringResource(Res.string.updates_check_now))
            }
        }
    }
}
