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
import androidx.compose.material3.Scaffold
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
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.Changes
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.events_to_settle
import com.jacekgajek.kapelusz.app.generated.nothing_to_settle
import com.jacekgajek.kapelusz.app.generated.open_event
import com.jacekgajek.kapelusz.app.generated.pair_names
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_part
import com.jacekgajek.kapelusz.app.generated.tab_settle
import com.jacekgajek.kapelusz.app.generated.you_hold
import com.jacekgajek.kapelusz.app.generated.your_transfers
import com.jacekgajek.kapelusz.app.ui.components.MessageHost
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
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.Payees
import com.jacekgajek.kapelusz.settlement.Settlement
import com.jacekgajek.kapelusz.text.BlikPhone
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

/** Everything the current user still has to pay, receive or hand over, across events. */
@Composable
fun SettleScreen(
    changes: Changes,
    state: BandState.Ready,
    onOpenEvent: (String) -> Unit,
    onSettings: () -> Unit,
    onRoster: () -> Unit,
) {
    val band = state.band
    val me = state.me
    val messages = rememberMessages()
    val actions = rememberActions(changes, messages, undos = null)
    val fundLabel = stringResource(Res.string.band_fund)
    val lang = language

    val active = band.events.filter { !it.archived }.sortedByDescending { it.date }.map { it to state.view.settlement(it) }
    val can = Capabilities(band, state.userId)
    // Only transfers the user may record (a viewer may not, e.g. one added to an event before that was refused); a
    // pair's are both partners' (ITERATION_5 §7).
    val mine = active.mapNotNull { (event, settlement) ->
        val myParties = (me?.let { settlement.party(it.id)?.partyIds } ?: listOfNotNull(me?.id)) +
            listOfNotNull(BAND_FUND.takeIf { me != null && band.treasurerMemberId == me.id })
        val suggestions = settlement.suggestions.filter { (it.from in myParties || it.to in myParties) && can.settleSuggestion(event, it.from, it.to) }
        if (settlement.canSettle && suggestions.isNotEmpty()) Triple(event, settlement, suggestions) else null
    }

    // What the user holds is their own, also in a pair; only what the pair still has to pass on counts (§7).
    fun held(settlement: Settlement): Money? {
        val own = settlement.line(me?.id ?: "")?.delta?.takeIf { it.isPositive } ?: return null
        val pair = settlement.party(me!!.id)?.delta?.takeIf { it.isPositive } ?: return null
        return minOf(own, pair)
    }
    val holding = active.filter { (event, settlement) ->
        settlement.status == EventStatus.OPEN && mine.none { it.first.id == event.id } && held(settlement) != null
    }
    val pair = stringResource(Res.string.pair_names)
    val forLeads = if (me?.role?.atLeast(Role.LEAD) == true) {
        // A booked gig with its fee entered is not business yet: awaiting payment counts once the gig is over.
        active.filter { (event, s) -> s.status == EventStatus.OPEN || s.status == EventStatus.LOSS || (s.status == EventStatus.AWAITING && event.date <= today()) }
    } else {
        emptyList()
    }

    Scaffold(
        topBar = { TabTopBar(stringResource(Res.string.tab_settle), onSettings, onRoster) },
        snackbarHost = { MessageHost(messages) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mine.isEmpty() && holding.isEmpty() && forLeads.isEmpty()) {
                item { Text(stringResource(Res.string.nothing_to_settle), Modifier.padding(vertical = 32.dp)) }
            }
            if (mine.isNotEmpty()) item { SectionTitle(stringResource(Res.string.your_transfers)) }
            items(mine, key = { "mine-${it.first.id}" }) { (event, settlement, suggestions) ->
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
                                        val keepsForFund = suggestion.to == BAND_FUND && band.treasurerMemberId != null &&
                                            band.treasurerMemberId in (settlement.party(suggestion.from)?.partyIds ?: listOf(suggestion.from))
                                        if (!keepsForFund && BlikPhone.pays(band, settlement, me?.id, suggestion.from)) PaymentButtons(Payees.forTransfer(band, settlement, suggestion.from, suggestion.to, suggestion.amount, me?.id))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MoneyText(suggestion.amount, band.currency, bold = true, modifier = Modifier.weight(1f))
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
            items(holding, key = { "holding-${it.first.id}" }) { (event, settlement) ->
                Card(onClick = { onOpenEvent(event.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            Res.string.you_hold,
                            formatMoney(held(settlement)!!, band.currency, lang),
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
