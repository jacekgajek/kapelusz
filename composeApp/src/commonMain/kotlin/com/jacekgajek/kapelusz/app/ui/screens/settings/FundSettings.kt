package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.fund_cannot_disable
import com.jacekgajek.kapelusz.app.generated.fund_disable_confirm
import com.jacekgajek.kapelusz.app.generated.fund_disable_text
import com.jacekgajek.kapelusz.app.generated.fund_disable_title
import com.jacekgajek.kapelusz.app.generated.fund_disabled_hint
import com.jacekgajek.kapelusz.app.generated.fund_enable
import com.jacekgajek.kapelusz.app.generated.fund_explanation
import com.jacekgajek.kapelusz.app.generated.fund_status
import com.jacekgajek.kapelusz.app.generated.fund_summary_off
import com.jacekgajek.kapelusz.app.generated.fund_summary_on
import com.jacekgajek.kapelusz.app.generated.fund_treasurer
import com.jacekgajek.kapelusz.app.generated.fund_treasurer_choose
import com.jacekgajek.kapelusz.app.generated.fund_treasurer_hint
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import org.jetbrains.compose.resources.stringResource

/** The band fund page: what it is, whether it is on and who holds it (admins change it). */
@Composable
internal fun FundSettings(
    treasurer: String?,
    treasurerName: String?,
    treasurerOptions: List<Pair<String, String>>,
    onTreasurer: ((String) -> Unit)?,
    onDisableFund: (() -> Unit)?,
    fundBalance: String,
) {
    Text(stringResource(Res.string.fund_explanation), style = MaterialTheme.typography.bodyMedium)
    val names = treasurerOptions.toMap()
    if (onTreasurer == null) {
        SectionHeader(stringResource(Res.string.fund_status))
        Text(treasurerName?.let { stringResource(Res.string.fund_summary_on, it) } ?: stringResource(Res.string.fund_summary_off))
        return
    }
    // On = someone holds the fund. Turning it on asks for the holder first; the switch shows on meanwhile.
    var enabling by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    val enabled = treasurer != null
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp)
            .toggleable(
                value = enabled || enabling,
                enabled = !enabled || onDisableFund != null,
                role = Role.Switch,
                onValueChange = { on -> if (on) enabling = true else if (enabled) confirmDisable = true else enabling = false },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.fund_enable), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled || enabling, onCheckedChange = null, enabled = !enabled || onDisableFund != null)
    }
    if (enabled || enabling) {
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
            Text(stringResource(Res.string.fund_cannot_disable, fundBalance), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
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
