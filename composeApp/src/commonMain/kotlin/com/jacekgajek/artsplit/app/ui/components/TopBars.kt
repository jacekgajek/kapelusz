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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.RowScope
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.back
import com.jacekgajek.artsplit.app.generated.menu_roster
import com.jacekgajek.artsplit.app.generated.menu_settings
import com.jacekgajek.artsplit.app.generated.new_band
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
            bar?.let { SyncIndicator(it.syncStatus, it.onSyncNow) }
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
private fun SyncIndicator(status: SyncStatus, onSyncNow: () -> Unit) {
    val (icon, label) = when (status) {
        SyncStatus.IDLE, SyncStatus.SYNCING -> Icons.Default.CloudSync to Res.string.sync_syncing
        SyncStatus.SYNCED -> Icons.Default.CloudDone to Res.string.sync_synced
        SyncStatus.OFFLINE -> Icons.Default.CloudOff to Res.string.sync_offline
        SyncStatus.NEEDS_SIGN_IN -> Icons.Default.Warning to Res.string.sync_needs_sign_in
        SyncStatus.ERROR -> Icons.Default.Warning to Res.string.sync_error
    }
    val tint = if (status == SyncStatus.NEEDS_SIGN_IN || status == SyncStatus.ERROR) MaterialTheme.colorScheme.error else LocalContentColor.current
    IconButton(onClick = onSyncNow) { Icon(icon, contentDescription = stringResource(label), tint = tint) }
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
