package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.jetbrains.compose.resources.stringResource

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
            Text(stringResource(Res.string.account), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            account?.let { Text(it, Modifier.padding(vertical = 8.dp)) }
            OutlinedButton(onClick = onSignOut) { Text(stringResource(Res.string.sign_out)) }
        }
    }
}
