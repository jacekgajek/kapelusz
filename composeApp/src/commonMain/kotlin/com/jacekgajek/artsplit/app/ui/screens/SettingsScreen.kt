package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.generated.language
import com.jacekgajek.artsplit.app.generated.language_en
import com.jacekgajek.artsplit.app.generated.language_pl
import com.jacekgajek.artsplit.app.generated.language_system
import com.jacekgajek.artsplit.app.generated.settings
import com.jacekgajek.artsplit.app.generated.sign_out
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    language: String?,
    onLanguage: (String?) -> Unit,
    account: String?,
    onSignOut: () -> Unit,
    bandName: String,
    onRenameBand: ((String) -> Unit)?,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(stringResource(Res.string.settings), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
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
            if (onRenameBand != null) {
                var name by remember(bandName) { mutableStateOf(bandName) }
                Text(stringResource(Res.string.band), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.band_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank() && name.trim() != bandName, onClick = { onRenameBand(name.trim()) }) {
                        Text(stringResource(Res.string.save))
                    }
                }
            }
            Text(stringResource(Res.string.account), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            account?.let { Text(it, Modifier.padding(vertical = 8.dp)) }
            OutlinedButton(onClick = onSignOut) { Text(stringResource(Res.string.sign_out)) }
        }
    }
}
