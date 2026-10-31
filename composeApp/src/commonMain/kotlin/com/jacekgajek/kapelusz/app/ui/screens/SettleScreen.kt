package com.jacekgajek.kapelusz.app.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.events_to_settle
import com.jacekgajek.kapelusz.app.generated.nothing_to_settle
import com.jacekgajek.kapelusz.app.generated.open_event
import com.jacekgajek.kapelusz.app.generated.pair_names
import com.jacekgajek.kapelusz.app.generated.settle_part
import com.jacekgajek.kapelusz.app.generated.tab_settle
import com.jacekgajek.kapelusz.app.generated.you_hold
import com.jacekgajek.kapelusz.app.generated.your_transfers
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.TabNav
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.PartialSettleDialog
import com.jacekgajek.kapelusz.app.ui.components.PaymentButtons
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.StatusChip
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.TransferParties
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.components.settleLabel
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.settlement.Payees
import com.jacekgajek.kapelusz.text.BlikPhone
import com.jacekgajek.kapelusz.view.Settling
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

/** Everything the current user still has to pay, receive or hand over, across events. */
@Composable
fun SettleScreen(session: BandSession, nav: TabNav) {
    val state = session.state
    val changes = session.changes
    val onOpenEvent: (String) -> Unit = nav::openEvent
    val band = state.band
    val me = state.me
    val messages = rememberMessages()
    val actions = rememberActions(changes, messages, undos = null)
    val fundLabel = stringResource(Res.string.band_fund)
    val lang = language

    val settling = remember(state, me) { Settling(state.view, me, session.can, today()) }
    val pair = stringResource(Res.string.pair_names)

    AppScaffold(
        messages,
        topBar = { TabTopBar(stringResource(Res.string.tab_settle)) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (settling.isEmpty) {
                item { Text(stringResource(Res.string.nothing_to_settle), Modifier.padding(vertical = 32.dp)) }
            }
            if (settling.mine.isNotEmpty()) item { SectionTitle(stringResource(Res.string.your_transfers)) }
            items(settling.mine, key = { "mine-${it.event.id}" }) { (event, settlement, suggestions) ->
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
                        suggestions.forEach { suggestion ->
                            key(suggestion.from, suggestion.to) {
                                val from = partyName(band, event, settlement, suggestion.from, fundLabel, pair)
                                val to = partyName(band, event, settlement, suggestion.to, fundLabel, pair)
                                var partial by remember { mutableStateOf(false) }
                                // Names over the full width; the amount and the buttons below, so long names never squeeze them.
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TransferParties(from, to, Modifier.weight(1f))
                                        // Only where you pay (ITERATION_5 §8); none when the fund's holder keeps it.
                                        val keepsForFund = Settling.keepsForFund(band, settlement, suggestion)
                                        if (!keepsForFund && BlikPhone.pays(band, settlement, me?.id, suggestion.from)) PaymentButtons(Payees.forTransfer(band, settlement, suggestion.from, suggestion.to, suggestion.amount, me?.id))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MoneyText(suggestion.amount, bold = true, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { partial = true }) { Text(stringResource(Res.string.settle_part)) }
                                        FilledTonalButton(onClick = {
                                            actions.run(RecordTransfer(event.id, newId(), suggestion.from, suggestion.to, suggestion.amount, together = true))
                                        }) { Text(stringResource(settleLabel(band, settlement, me?.id, suggestion.from, suggestion.to))) }
                                    }
                                }
                                if (partial) {
                                    PartialSettleDialog(from, to, suggestion.amount, band.currency, onConfirm = {
                                        actions.run(RecordTransfer(event.id, newId(), suggestion.from, suggestion.to, it, together = true))
                                    }, onDismiss = { partial = false }, confirmLabel = settleLabel(band, settlement, me?.id, suggestion.from, suggestion.to), actions = actions)
                                }
                            }
                        }
                    }
                }
            }
            items(settling.holding, key = { "holding-${it.event.id}" }) { (event, settlement) ->
                Card(onClick = { onOpenEvent(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            Res.string.you_hold,
                            formatMoney(settling.held(settlement)!!, band.currency, lang),
                            event.name,
                        ),
                        Modifier.padding(16.dp),
                    )
                }
            }
            if (settling.forLeads.isNotEmpty()) item { SectionTitle(stringResource(Res.string.events_to_settle)) }
            items(settling.forLeads, key = { "lead-${it.event.id}" }) { (event, settlement) ->
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
