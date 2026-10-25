package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.ui.components.listWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.cost_date
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.description
import com.jacekgajek.kapelusz.app.generated.fund_add_entry
import com.jacekgajek.kapelusz.app.generated.fund_balance
import com.jacekgajek.kapelusz.app.generated.fund_entries
import com.jacekgajek.kapelusz.app.generated.fund_entries_empty
import com.jacekgajek.kapelusz.app.generated.fund_from_events
import com.jacekgajek.kapelusz.app.generated.fund_held_by
import com.jacekgajek.kapelusz.app.generated.fund_in
import com.jacekgajek.kapelusz.app.generated.fund_no_treasurer
import com.jacekgajek.kapelusz.app.generated.fund_no_treasurer_admin
import com.jacekgajek.kapelusz.app.generated.fund_not_handed_over
import com.jacekgajek.kapelusz.app.generated.fund_out
import com.jacekgajek.kapelusz.app.generated.fund_refund_cost
import com.jacekgajek.kapelusz.app.generated.fund_refund_none
import com.jacekgajek.kapelusz.app.generated.fund_handed_over
import com.jacekgajek.kapelusz.app.generated.fund_waiting
import com.jacekgajek.kapelusz.app.generated.open_settings
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sumOfMoney
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

/** The band fund ("Kasa zespołowa"): balance, contributions from events, and manual entries. */
@Composable
fun BandFundScreen(graph: AppGraph, state: BandState.Ready, onSettings: () -> Unit, onRoster: () -> Unit, onOpenEvent: (String) -> Unit) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val can = Can(band, state.userId)
    val canEdit = band.treasurerMemberId != null && can.allowed(AddFundEntry("probe", today(), "probe", Money(1)))
    var editing by remember { mutableStateOf<FundEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val lang = language

    Scaffold(
        topBar = { TabTopBar(stringResource(Res.string.tab_fund), onSettings, onRoster) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canEdit) ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(Res.string.fund_add_entry)) },
            )
        },
    ) { padding ->
        val treasurer = band.treasurerMemberId?.let { band.member(it) }
        if (treasurer == null) {
            Column(Modifier.fillMaxSize().padding(padding).listWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.fund_no_treasurer))
                if (can.manageRoster()) {
                    Text(stringResource(Res.string.fund_no_treasurer_admin), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = onSettings) { Text(stringResource(Res.string.open_settings)) }
                }
            }
            return@Scaffold
        }
        val contributions = band.events.filter { !it.fundAmount.isZero }.sortedByDescending { it.date }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(stringResource(Res.string.fund_balance), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            MoneyText(BandFund.balance(band), band.currency, colored = true, bold = true)
                        }
                        val waiting = BandFund.notHandedOver(band)
                        if (!waiting.isZero) {
                            Row {
                                Text(stringResource(Res.string.fund_not_handed_over), Modifier.weight(1f))
                                MoneyText(waiting, band.currency)
                            }
                        }
                        Text(stringResource(Res.string.fund_held_by, treasurer.name), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (contributions.isNotEmpty()) {
                item { SectionTitle(stringResource(Res.string.fund_from_events)) }
                items(contributions, key = { "event-${it.id}" }) { event ->
                    // Counts a fee paid straight to the fund too (ITERATION_4 §2.2).
                    val handedOver = BandFund.isHandedOver(event)
                    Row(Modifier.fillMaxWidth().clickable { onOpenEvent(event.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(event.name)
                            Text(
                                "${formatDate(event.date, lang)} · " + stringResource(if (handedOver) Res.string.fund_handed_over else Res.string.fund_waiting),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        MoneyText(event.fundAmount, band.currency, colored = true)
                    }
                }
            }
            item { SectionTitle(stringResource(Res.string.fund_entries)) }
            if (band.fundEntries.isEmpty()) item { Text(stringResource(Res.string.fund_entries_empty), style = MaterialTheme.typography.bodyMedium) }
            items(band.fundEntries.sortedByDescending { it.date }, key = { "entry-${it.id}" }) { entry ->
                Row(Modifier.fillMaxWidth().clickable(enabled = canEdit) { editing = entry }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.description)
                        Text(formatDate(entry.date, lang), style = MaterialTheme.typography.bodySmall)
                    }
                    MoneyText(entry.amount, band.currency, colored = true)
                    if (canEdit) EditButton { editing = entry }
                }
            }
        }
    }

    if (adding) FundEntryDialog(band, null, onSave = { actions.run(it) }, onDelete = null, onDismiss = { adding = false })
    editing?.let { entry ->
        FundEntryDialog(band, entry, onSave = { actions.run(it) }, onDelete = { actions.run(RemoveFundEntry(entry.id)) }, onDismiss = { editing = null })
    }
}

/** Add or edit a manual entry: money into or out of the fund, optionally refunding a member's pending cost. */
@Composable
private fun FundEntryDialog(
    band: Band,
    initial: FundEntry?,
    onSave: (Operation) -> Boolean,
    onDelete: (() -> Boolean)?,
    onDismiss: () -> Unit,
) {
    val lang = language
    var incoming by remember { mutableStateOf(initial?.amount?.isPositive ?: false) }
    var amount by remember { mutableStateOf(initial?.let { formatMoneyInput(Money(abs(it.amount.minor)), lang) } ?: "") }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date ?: today()) }
    // Refunding a cost is only offered for new entries; the link cannot be changed later.
    val pendingCosts = band.costs.filter { CostLedger.outstanding(band, it).isPositive }
    var costId by remember { mutableStateOf(initial?.costId) }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }
    val signed = parsed?.let { if (incoming) it else -it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.band_fund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    Dropdown(costId, listOf<String?>(null) + pendingCosts.map { it.id }, { id ->
                        costId = id
                        val cost = id?.let { band.cost(it) }
                        if (cost != null) {
                            incoming = false
                            amount = formatMoneyInput(CostLedger.outstanding(band, cost), lang)
                            if (description.isBlank()) description = cost.description.orEmpty()
                        }
                    }, stringResource(Res.string.fund_refund_cost)) { id ->
                        id?.let { band.cost(it) }?.let { cost ->
                            "${band.member(cost.memberId)?.name.orEmpty()}: ${cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost)} · " +
                                formatMoney(CostLedger.outstanding(band, cost), band.currency, lang)
                        } ?: stringResource(Res.string.fund_refund_none)
                    }
                }
                if (costId == null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(true to Res.string.fund_in, false to Res.string.fund_out).forEachIndexed { index, (value, label) ->
                            SegmentedButton(selected = incoming == value, onClick = { incoming = value }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(Res.string.description)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(Res.string.cost_date))
            }
        },
        confirmButton = {
            TextButton(enabled = signed != null && description.isNotBlank(), onClick = {
                val ok = if (initial == null) {
                    onSave(AddFundEntry(newId(), date, description.trim(), signed!!, costId))
                } else {
                    onSave(
                        UpdateFundEntry(
                            initial.id,
                            date = Change(date).takeIf { date != initial.date },
                            description = Change(description.trim()).takeIf { description.trim() != initial.description },
                            amount = Change(signed!!).takeIf { signed != initial.amount },
                        ),
                    )
                }
                if (ok) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { if (onDelete()) onDismiss() }) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
