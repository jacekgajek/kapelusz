package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.fund_default
import com.jacekgajek.kapelusz.app.generated.fund_default_apply_text
import com.jacekgajek.kapelusz.app.generated.fund_default_hint
import com.jacekgajek.kapelusz.app.generated.fund_default_none
import com.jacekgajek.kapelusz.app.generated.member_share_apply_no
import com.jacekgajek.kapelusz.app.generated.member_share_apply_title
import com.jacekgajek.kapelusz.app.generated.member_share_apply_yes
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.ui.components.FundCutEditor
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberFundCutEditorState
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.model.FundCut
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

/**
 * Changing the fund's default for new events (ITERATION_7 §2g): the unsettled events that still have the old one (id and
 * name), offered the new one too, as a member's default share is; [onSave] saves it, and to the events chosen.
 */
class FundDefaultSetting(val stillDefault: List<Pair<String, String>>, val onSave: (FundCut?, List<String>) -> Unit)

/** The band fund page: what it is, whether it is on, who holds it and its default for new events (leaders change it). */
@Composable
internal fun FundSettings(
    treasurer: String?,
    treasurerName: String?,
    treasurerOptions: List<Pair<String, String>>,
    onTreasurer: ((String) -> Unit)?,
    onDisableFund: (() -> Unit)?,
    fundBalance: String,
    fundDefault: FundCut?,
    fundDefaultSetting: FundDefaultSetting?,
    currency: String,
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
        if (enabled) FundDefaultItem(fundDefault, fundDefaultSetting, currency)
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

@Composable
private fun FundDefaultItem(fundDefault: FundCut?, setting: FundDefaultSetting?, currency: String) {
    var editing by remember { mutableStateOf(false) }
    val lang = language
    val summary = fundDefault?.percent?.let { "$it%" } ?: fundDefault?.amount?.let { formatMoney(it, currency, lang) }
        ?: stringResource(Res.string.fund_default_none)
    Box(Modifier.padding(top = 8.dp)) {
        SettingsItem(Icons.Default.Percent, stringResource(Res.string.fund_default), summary, onClick = setting?.let { { editing = true } })
    }
    if (editing && setting != null) FundDefaultDialog(fundDefault, setting, currency) { editing = false }
}

/** The fund's default, then (if some unsettled events still have the old one) whether to use the new one there too. */
@Composable
private fun FundDefaultDialog(fundDefault: FundCut?, setting: FundDefaultSetting, currency: String, onDismiss: () -> Unit) {
    val editor = rememberFundCutEditorState(fundDefault)
    var applying by remember { mutableStateOf<FundCut?>(null) }
    var asking by remember { mutableStateOf(false) }
    fun save(cut: FundCut?, events: List<String>) {
        setting.onSave(cut, events)
        onDismiss()
    }
    if (!asking) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.fund_default)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FundCutEditor(editor, currency, profit = null)
                    Text(stringResource(Res.string.fund_default_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = editor.valid, onClick = {
                    val cut = editor.cut
                    when {
                        cut == fundDefault -> onDismiss()
                        setting.stillDefault.isEmpty() -> save(cut, emptyList())
                        else -> {
                            applying = cut
                            asking = true
                        }
                    }
                }) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        )
    } else {
        val events = setting.stillDefault
        val names = events.take(3).joinToString(", ") { it.second } + if (events.size > 3) "…" else ""
        AlertDialog(
            // Save was pressed already: leaving the question keeps the new default, without touching events.
            onDismissRequest = { save(applying, emptyList()) },
            title = { Text(stringResource(Res.string.member_share_apply_title)) },
            text = { Text(stringResource(Res.string.fund_default_apply_text, events.size, names)) },
            confirmButton = { TextButton(onClick = { save(applying, events.map { it.first }) }) { Text(stringResource(Res.string.member_share_apply_yes)) } },
            dismissButton = { TextButton(onClick = { save(applying, emptyList()) }) { Text(stringResource(Res.string.member_share_apply_no)) } },
        )
    }
}
