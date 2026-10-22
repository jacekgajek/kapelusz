package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.app_name
import com.jacekgajek.kapelusz.app.generated.create_band_intro
import com.jacekgajek.kapelusz.app.generated.join_band
import com.jacekgajek.kapelusz.app.generated.new_band_title
import com.jacekgajek.kapelusz.app.generated.open_band
import com.jacekgajek.kapelusz.app.generated.open_band_failed
import com.jacekgajek.kapelusz.app.generated.sign_in_again_title
import com.jacekgajek.kapelusz.app.generated.sync_error
import com.jacekgajek.kapelusz.app.generated.sync_needs_sign_in
import com.jacekgajek.kapelusz.app.generated.sync_offline
import com.jacekgajek.kapelusz.app.sync.SyncStatus
import com.jacekgajek.kapelusz.app.generated.or_create_band
import com.jacekgajek.kapelusz.app.generated.sign_out
import com.jacekgajek.kapelusz.app.generated.your_bands
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.label
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when no band is open: bands the user already belongs to (e.g. on a new device), creating a band, and joining
 * one (invites arrive in M4).
 */
@Composable
fun ZeroScreen(
    bands: List<BandSummary>,
    syncStatus: SyncStatus,
    openError: Boolean,
    onSignInAgain: () -> Unit,
    defaultYourName: String,
    onOpen: (String) -> Unit,
    onCreate: (name: String, currency: String, yourName: String) -> Unit,
    onSignOut: () -> Unit,
    onJoin: () -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 32.dp))
            val checking = syncStatus == SyncStatus.IDLE || syncStatus == SyncStatus.SYNCING
            if (checking && bands.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            when (syncStatus) {
                SyncStatus.NEEDS_SIGN_IN -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.sync_needs_sign_in))
                        FilledTonalButton(onClick = onSignInAgain) { Text(stringResource(Res.string.sign_in_again_title)) }
                    }
                }
                SyncStatus.OFFLINE -> Text(stringResource(Res.string.sync_offline), color = MaterialTheme.colorScheme.error)
                SyncStatus.ERROR -> Text(stringResource(Res.string.sync_error), color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            if (openError) Text(stringResource(Res.string.open_band_failed), color = MaterialTheme.colorScheme.error)
            if (bands.isNotEmpty()) {
                Text(stringResource(Res.string.your_bands), style = MaterialTheme.typography.titleMedium)
                bands.forEach { band ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(band.name, style = MaterialTheme.typography.titleSmall)
                                Text("${band.memberName} · ${band.role.label()}", style = MaterialTheme.typography.bodySmall)
                            }
                            FilledTonalButton(onClick = { onOpen(band.id) }) { Text(stringResource(Res.string.open_band)) }
                        }
                    }
                }
                Text(stringResource(Res.string.or_create_band), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            } else {
                Text(stringResource(Res.string.create_band_intro), style = MaterialTheme.typography.bodyMedium)
            }
            CreateBandForm(onCreate, defaultYourName)
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.join_band)) }
            TextButton(onClick = onSignOut) { Text(stringResource(Res.string.sign_out)) }
        }
    }
}

/** Creating another band from the band switcher. */
@Composable
fun NewBandScreen(defaultYourName: String, onCreate: (name: String, currency: String, yourName: String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.new_band_title), onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.TopCenter) {
            CreateBandForm(onCreate, defaultYourName)
        }
    }
}
