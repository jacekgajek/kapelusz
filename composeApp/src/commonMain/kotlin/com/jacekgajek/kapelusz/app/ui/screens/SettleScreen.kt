package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.events_to_settle
import com.jacekgajek.kapelusz.app.generated.nothing_to_settle
import com.jacekgajek.kapelusz.app.generated.open_event
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_part
import com.jacekgajek.kapelusz.app.generated.tab_settle
import com.jacekgajek.kapelusz.app.generated.you_hold
import com.jacekgajek.kapelusz.app.generated.your_transfers
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.PartialSettleDialog
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.TransferParties
import com.jacekgajek.kapelusz.app.ui.components.StatusChip
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.RecordTransfer
import org.jetbrains.compose.resources.stringResource

/** Everything the current user still has to pay, receive or hand over, across events. */
@Composable
fun SettleScreen(
    graph: AppGraph,
    state: BandState.Ready,
    onOpenEvent: (String) -> Unit,
    onSettings: () -> Unit,
    onRoster: () -> Unit,
) {
    val band = state.band
    val me = state.me
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val fundLabel = stringResource(Res.string.band_fund)
    val lang = language

    val active = band.events.filter { !it.archived }.sortedByDescending { it.date }.map { it to SettlementCalculator.settle(band, it) }
    val myParties = setOfNotNull(me?.id, BAND_FUND.takeIf { me != null && band.treasurerMemberId == me.id })
    val mine = active.mapNotNull { (event, settlement) ->
        val suggestions = settlement.suggestions.filter { it.from in myParties || it.to in myParties }
        if (settlement.canSettle && suggestions.isNotEmpty()) Triple(event, settlement, suggestions) else null
    }
    val holding = active.filter { (event, settlement) ->
        settlement.status == EventStatus.OPEN && mine.none { it.first.id == event.id } &&
            (settlement.line(me?.id ?: "")?.delta?.isPositive == true)
    }
    val forLeads = if (me?.role?.atLeast(Role.LEAD) == true) {
        active.filter { (_, s) -> s.status == EventStatus.OPEN || s.status == EventStatus.LOSS }
    } else {
        emptyList()
    }

    Scaffold(
        topBar = { TabTopBar(stringResource(Res.string.tab_settle), onSettings, onRoster) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mine.isEmpty() && holding.isEmpty() && forLeads.isEmpty()) {
                item { Text(stringResource(Res.string.nothing_to_settle), Modifier.padding(vertical = 32.dp)) }
            }
            if (mine.isNotEmpty()) item { SectionTitle(stringResource(Res.string.your_transfers)) }
            items(mine, key = { "mine-${it.first.id}" }) { (event, _, suggestions) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${event.name} · ${formatDate(event.date, lang)}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(onClick = { onOpenEvent(event.id) }) { Text(stringResource(Res.string.open_event)) }
                        }
                        // Keyed by the parties: an open "Part" dialog stays with its people when a sync reorders the list.
                        suggestions.forEach { suggestion -> key(suggestion.from, suggestion.to) {
                            val from = partyName(band, event, suggestion.from, fundLabel)
                            val to = partyName(band, event, suggestion.to, fundLabel)
                            var partial by remember { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    TransferParties(from, to)
                                    MoneyText(suggestion.amount, band.currency, bold = true)
                                }
                                TextButton(onClick = { partial = true }) { Text(stringResource(Res.string.settle_part)) }
                                FilledTonalButton(onClick = {
                                    actions.run(RecordTransfer(event.id, newId(), suggestion.from, suggestion.to, suggestion.amount))
                                }) { Text(stringResource(Res.string.settle)) }
                            }
                            if (partial) {
                                PartialSettleDialog(from, to, suggestion.amount, band.currency, onConfirm = {
                                    actions.run(RecordTransfer(event.id, newId(), suggestion.from, suggestion.to, it))
                                }, onDismiss = { partial = false })
                            }
                        } }
                    }
                }
            }
            items(holding, key = { "holding-${it.first.id}" }) { (event, settlement) ->
                Card(onClick = { onOpenEvent(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            Res.string.you_hold,
                            formatMoney(settlement.line(me!!.id)!!.delta, band.currency, lang),
                            event.name,
                        ),
                        Modifier.padding(16.dp),
                    )
                }
            }
            if (forLeads.isNotEmpty()) item { SectionTitle(stringResource(Res.string.events_to_settle)) }
            items(forLeads, key = { "lead-${it.first.id}" }) { (event, settlement) ->
                Card(onClick = { onOpenEvent(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(event.name, style = MaterialTheme.typography.titleSmall)
                            Text(formatDate(event.date, lang), style = MaterialTheme.typography.bodySmall)
                        }
                        StatusChip(settlement.status)
                    }
                }
            }
        }
    }
}
