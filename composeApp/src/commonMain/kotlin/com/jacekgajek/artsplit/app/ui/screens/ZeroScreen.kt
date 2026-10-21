package com.jacekgajek.artsplit.app.ui.screens

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
import com.jacekgajek.artsplit.api.BandSummary
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.app_name
import com.jacekgajek.artsplit.app.generated.create_band_intro
import com.jacekgajek.artsplit.app.generated.join_band
import com.jacekgajek.artsplit.app.generated.join_band_soon
import com.jacekgajek.artsplit.app.generated.new_band_title
import com.jacekgajek.artsplit.app.generated.open_band
import com.jacekgajek.artsplit.app.generated.or_create_band
import com.jacekgajek.artsplit.app.generated.sign_out
import com.jacekgajek.artsplit.app.generated.your_bands
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.label
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when no band is open: bands the user already belongs to (e.g. on a new device), creating a band, and joining
 * one (invites arrive in M4).
 */
@Composable
fun ZeroScreen(
    bands: List<BandSummary>,
    checking: Boolean,
    defaultYourName: String,
    onOpen: (String) -> Unit,
    onCreate: (name: String, currency: String, yourName: String) -> Unit,
    onSignOut: () -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 32.dp))
            if (checking && bands.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
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
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.join_band)) }
            Text(stringResource(Res.string.join_band_soon), style = MaterialTheme.typography.bodySmall)
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
