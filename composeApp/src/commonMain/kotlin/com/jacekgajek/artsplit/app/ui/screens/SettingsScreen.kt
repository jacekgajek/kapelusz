package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.account
import com.jacekgajek.artsplit.app.generated.band
import com.jacekgajek.artsplit.app.generated.band_name
import com.jacekgajek.artsplit.app.generated.fund_treasurer
import com.jacekgajek.artsplit.app.generated.fund_treasurer_hint
import com.jacekgajek.artsplit.app.generated.fund_treasurer_choose
import com.jacekgajek.artsplit.app.generated.fund_enable
import com.jacekgajek.artsplit.app.generated.fund_cannot_disable
import com.jacekgajek.artsplit.app.generated.fund_disabled_hint
import com.jacekgajek.artsplit.app.generated.fund_disable_title
import com.jacekgajek.artsplit.app.generated.fund_disable_text
import com.jacekgajek.artsplit.app.generated.fund_disable_confirm
import com.jacekgajek.artsplit.app.ui.components.ConfirmDialog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.generated.language
import com.jacekgajek.artsplit.app.generated.language_en
import com.jacekgajek.artsplit.app.generated.language_pl
import com.jacekgajek.artsplit.app.generated.language_system
import com.jacekgajek.artsplit.app.generated.settings
import com.jacekgajek.artsplit.app.generated.sign_out
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.Dropdown
import com.jacekgajek.artsplit.app.ui.components.VersionLabel
import com.jacekgajek.artsplit.app.platform.UpdateControls
import com.jacekgajek.artsplit.app.platform.UpdateStatus
import com.jacekgajek.artsplit.app.platform.rememberUpdateControls
import androidx.compose.runtime.collectAsState
import com.jacekgajek.artsplit.app.generated.updates_title
import com.jacekgajek.artsplit.app.generated.updates_auto
import com.jacekgajek.artsplit.app.generated.updates_installing
import com.jacekgajek.artsplit.app.generated.updates_declined
import com.jacekgajek.artsplit.app.generated.updates_install_failed
import com.jacekgajek.artsplit.app.generated.updates_check_now
import com.jacekgajek.artsplit.app.generated.updates_checking
import com.jacekgajek.artsplit.app.generated.updates_up_to_date
import com.jacekgajek.artsplit.app.generated.updates_downloading
import com.jacekgajek.artsplit.app.generated.updates_ready
import com.jacekgajek.artsplit.app.generated.updates_install_now
import com.jacekgajek.artsplit.app.generated.updates_needs_permission
import com.jacekgajek.artsplit.app.generated.updates_allow
import com.jacekgajek.artsplit.app.generated.updates_needs_confirmation
import com.jacekgajek.artsplit.app.generated.updates_install
import com.jacekgajek.artsplit.app.generated.updates_failed
import com.jacekgajek.artsplit.app.generated.updates_unavailable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.jacekgajek.artsplit.app.generated.delete_band
import com.jacekgajek.artsplit.app.generated.delete_band_title
import com.jacekgajek.artsplit.app.generated.delete_band_everything
import com.jacekgajek.artsplit.app.generated.delete_band_members
import com.jacekgajek.artsplit.app.generated.delete_band_no_members
import com.jacekgajek.artsplit.app.generated.delete_band_unsynced
import com.jacekgajek.artsplit.app.generated.delete_band_final
import com.jacekgajek.artsplit.app.generated.delete_band_type_name
import com.jacekgajek.artsplit.app.generated.delete_band_confirm
import com.jacekgajek.artsplit.app.generated.delete_band_failed
import com.jacekgajek.artsplit.app.generated.cancel

@Composable
fun SettingsScreen(
    language: String?,
    onLanguage: (String?) -> Unit,
    account: String?,
    onSignOut: () -> Unit,
    bandName: String,
    onRenameBand: ((String) -> Unit)?,
    treasurer: String?,
    treasurerOptions: List<Pair<String, String>>,
    onTreasurer: ((String) -> Unit)?,
    /** Turns the band fund off; null while it holds or owes money ([fundBalance] says how much it holds). */
    onDisableFund: (() -> Unit)?,
    fundBalance: String,
    /** Deletes the band for everyone; only for its owner. Throws when it did not work (e.g. offline). */
    onDeleteBand: (suspend () -> Unit)?,
    otherMembers: Int,
    autoUpdate: Boolean,
    onAutoUpdate: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.settings), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(Res.string.language), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                listOf(
                    null to Res.string.language_system,
                    "en" to Res.string.language_en,
                    "pl" to Res.string.language_pl,
                ).forEach { (code, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = language == code, onClick = { onLanguage(code) }, role = Role.RadioButton)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == code, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (onRenameBand != null || onTreasurer != null) {
                Text(stringResource(Res.string.band), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            }
            if (onRenameBand != null) {
                var name by remember(bandName) { mutableStateOf(bandName) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.band_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank() && name.trim() != bandName, onClick = { onRenameBand(name.trim()) }) {
                        Text(stringResource(Res.string.save))
                    }
                }
            }
            if (onTreasurer != null) {
                // On = someone holds the fund. Turning it on asks for the holder first; the switch shows on meanwhile.
                var enabling by remember { mutableStateOf(false) }
                var confirmDisable by remember { mutableStateOf(false) }
                val enabled = treasurer != null
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp)
                        .toggleable(
                            value = enabled || enabling,
                            enabled = !enabled || onDisableFund != null,
                            role = Role.Switch,
                            onValueChange = { on -> if (on) enabling = true else if (enabled) confirmDisable = true else enabling = false },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.fund_enable), Modifier.weight(1f))
                    Switch(checked = enabled || enabling, onCheckedChange = null, enabled = !enabled || onDisableFund != null)
                }
                if (enabled || enabling) {
                    val names = treasurerOptions.toMap()
                    Dropdown(
                        treasurer,
                        treasurerOptions.map { it.first },
                        {
                            it?.let(onTreasurer)
                            enabling = false
                        },
                        stringResource(Res.string.fund_treasurer),
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { id -> id?.let { names[it] } ?: stringResource(Res.string.fund_treasurer_choose) }
                    Text(stringResource(Res.string.fund_treasurer_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (enabled && onDisableFund == null) {
                        Text(
                            stringResource(Res.string.fund_cannot_disable, fundBalance),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Text(stringResource(Res.string.fund_disabled_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (confirmDisable && onDisableFund != null) {
                    ConfirmDialog(
                        title = stringResource(Res.string.fund_disable_title),
                        text = stringResource(Res.string.fund_disable_text),
                        confirmLabel = stringResource(Res.string.fund_disable_confirm),
                        onConfirm = onDisableFund,
                        onDismiss = { confirmDisable = false },
                    )
                }
            }
            if (onDeleteBand != null) {
                var deleting by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { deleting = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Icon(Icons.Default.DeleteForever, null)
                    Text(stringResource(Res.string.delete_band), Modifier.padding(start = 8.dp))
                }
                if (deleting) DeleteBandDialog(bandName, otherMembers, onDeleteBand, onDismiss = { deleting = false })
            }
            rememberUpdateControls()?.let { AppUpdatesSection(it, autoUpdate, onAutoUpdate) }
            Text(stringResource(Res.string.account), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            account?.let { Text(it, Modifier.padding(vertical = 8.dp)) }
            OutlinedButton(onClick = onSignOut) { Text(stringResource(Res.string.sign_out)) }
            VersionLabel(Modifier.align(Alignment.End).padding(top = 24.dp))
        }
    }
}

/**
 * The last step before deleting a band for everyone: every consequence spelled out, and the band's name typed out to
 * enable the (red) button, so it cannot happen by a stray tap.
 */
@Composable
private fun DeleteBandDialog(bandName: String, otherMembers: Int, onDelete: suspend () -> Unit, onDismiss: () -> Unit) {
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
                    if (otherMembers > 0) pluralStringResource(Res.plurals.delete_band_members, otherMembers, otherMembers)
                    else stringResource(Res.string.delete_band_no_members),
                )
                Text(stringResource(Res.string.delete_band_unsynced))
                Text(stringResource(Res.string.delete_band_final), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    typed,
                    { typed = it; failed = false },
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
                        try {
                            onDelete()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Throwable: on the web, network errors are JsException.
                            failed = true
                        }
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

/** Android: automatic updates (VERSION_2 §1): the switch, where the update stands, and what can be done about it. */
@Composable
private fun AppUpdatesSection(controls: UpdateControls, autoUpdate: Boolean, onAutoUpdate: (Boolean) -> Unit) {
    val status by controls.status.collectAsState()
    Text(stringResource(Res.string.updates_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
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
