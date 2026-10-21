package com.jacekgajek.artsplit.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.auth.GoogleSignInButton
import kotlinx.coroutines.launch
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.back
import com.jacekgajek.artsplit.app.generated.menu_roster
import com.jacekgajek.artsplit.app.generated.menu_settings
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.new_band
import com.jacekgajek.artsplit.app.generated.sign_in_again_text
import com.jacekgajek.artsplit.app.generated.sign_in_again_title
import com.jacekgajek.artsplit.app.generated.sign_in_again_wrong_account
import com.jacekgajek.artsplit.app.generated.sign_in_failed
import com.jacekgajek.artsplit.app.generated.settings
import com.jacekgajek.artsplit.app.generated.sync_error
import com.jacekgajek.artsplit.app.generated.sync_needs_sign_in
import com.jacekgajek.artsplit.app.generated.sync_offline
import com.jacekgajek.artsplit.app.generated.sync_synced
import com.jacekgajek.artsplit.app.generated.sync_syncing
import com.jacekgajek.artsplit.app.sync.SyncStatus
import com.jacekgajek.artsplit.api.BandSummary
import org.jetbrains.compose.resources.stringResource

/** What every tab's top bar shows besides the title: sync state and the band switcher. */
class TopBarState(
    val syncStatus: SyncStatus,
    val bands: List<BandSummary>,
    val currentBandId: String?,
    val onSelectBand: (String) -> Unit,
    val onNewBand: () -> Unit,
    val onSyncNow: () -> Unit,
    /** Signs in again with a fresh Google token; returns false if it was a different account. */
    val onReauthenticate: suspend (String) -> Boolean,
    val accountEmail: String?,
)

val LocalTopBarState = staticCompositionLocalOf<TopBarState?> { null }

/** Top bar of a tab screen: sync indicator and the cog menu (band switcher, band members, settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabTopBar(title: String, onSettings: () -> Unit, onRoster: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val bar = LocalTopBarState.current
    TopAppBar(
        title = { Text(title) },
        actions = {
            bar?.let { SyncIndicator(it) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.settings))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (bar != null) {
                    bar.bands.forEach { band ->
                        DropdownMenuItem(
                            text = { Text(band.name) },
                            leadingIcon = { if (band.id == bar.currentBandId) Icon(Icons.Default.Check, null) else Icon(Icons.Default.Groups, null) },
                            onClick = { menuOpen = false; if (band.id != bar.currentBandId) bar.onSelectBand(band.id) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.new_band)) },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { menuOpen = false; bar.onNewBand() },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text(stringResource(Res.string.menu_roster)) }, onClick = { menuOpen = false; onRoster() })
                DropdownMenuItem(text = { Text(stringResource(Res.string.menu_settings)) }, onClick = { menuOpen = false; onSettings() })
            }
        },
    )
}

@Composable
private fun SyncIndicator(bar: TopBarState) {
    val status = bar.syncStatus
    var signingIn by remember { mutableStateOf(false) }
    val (icon, label) = when (status) {
        SyncStatus.IDLE, SyncStatus.SYNCING -> Icons.Default.CloudSync to Res.string.sync_syncing
        SyncStatus.SYNCED -> Icons.Default.CloudDone to Res.string.sync_synced
        SyncStatus.OFFLINE -> Icons.Default.CloudOff to Res.string.sync_offline
        SyncStatus.NEEDS_SIGN_IN -> Icons.Default.Warning to Res.string.sync_needs_sign_in
        SyncStatus.ERROR -> Icons.Default.Warning to Res.string.sync_error
    }
    val tint = if (status == SyncStatus.NEEDS_SIGN_IN || status == SyncStatus.ERROR) MaterialTheme.colorScheme.error else LocalContentColor.current
    IconButton(onClick = { if (status == SyncStatus.NEEDS_SIGN_IN) signingIn = true else bar.onSyncNow() }) {
        Icon(icon, contentDescription = stringResource(label), tint = tint)
    }
    if (signingIn) SignInAgainDialog(bar, onDismiss = { signingIn = false })
}

/** Shown when sync needs a new Google sign-in (the old one could not be renewed without the user). */
@Composable
private fun SignInAgainDialog(bar: TopBarState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var wrongAccount by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sign_in_again_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.sign_in_again_text, bar.accountEmail.orEmpty()))
                GoogleSignInButton(
                    onIdToken = { token ->
                        scope.launch {
                            if (bar.onReauthenticate(token)) {
                                onDismiss()
                            } else {
                                wrongAccount = true
                            }
                        }
                    },
                    onError = { error = it },
                )
                if (wrongAccount) Text(stringResource(Res.string.sign_in_again_wrong_account, bar.accountEmail.orEmpty()), color = MaterialTheme.colorScheme.error)
                error?.let { Text(stringResource(Res.string.sign_in_failed, it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Top bar of a pushed screen, with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        },
        actions = actions,
    )
}
