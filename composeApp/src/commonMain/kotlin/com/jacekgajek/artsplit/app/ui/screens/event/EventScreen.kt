package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.edit_details
import com.jacekgajek.artsplit.app.generated.event_not_found
import com.jacekgajek.artsplit.app.generated.problem_loss
import com.jacekgajek.artsplit.app.generated.problem_no_shares
import com.jacekgajek.artsplit.app.generated.problem_no_treasurer
import com.jacekgajek.artsplit.app.generated.total_fund
import com.jacekgajek.artsplit.app.generated.total_income
import com.jacekgajek.artsplit.app.generated.total_net
import com.jacekgajek.artsplit.app.generated.total_refunds
import com.jacekgajek.artsplit.app.ui.components.Actions
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.MoneyText
import com.jacekgajek.artsplit.app.ui.components.StatusChip
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.components.rememberActions
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.domain.Settlement
import com.jacekgajek.artsplit.domain.SettlementCalculator
import com.jacekgajek.artsplit.domain.SettlementProblem
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.model.Member
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.app.ui.model.Can
import org.jetbrains.compose.resources.stringResource

/** Everything a section of the event screen needs. */
class EventContext(
    val band: Band,
    val event: Event,
    val settlement: Settlement,
    val me: Member?,
    val actions: Actions,
    val can: Can,
) {
    val currency: String get() = band.currency
}

@Composable
fun EventScreen(graph: AppGraph, state: BandState.Ready, eventId: String, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val event = state.band.event(eventId)
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(event?.name ?: "", onBack) {
                if (event != null && Can(state.band, state.userId).editEvent(event)) {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, stringResource(Res.string.edit_details)) }
                    EventMenu(state.band, event, actions, onDeleted = onBack)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (event == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.event_not_found))
            }
            return@Scaffold
        }
        val context = EventContext(state.band, event, SettlementCalculator.settle(state.band, event), state.me, actions, Can(state.band, state.userId))
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            ) {
                item { Header(context) }
                item { Problems(context) }
                item { ParticipantsSection(context) }
                item { CollectedSection(context) }
                item { RefundsSection(context) }
                item { SummarySection(context) }
                item { TransfersSection(context) }
            }
        }
        if (editing) EditDetailsDialog(event, actions, onDismiss = { editing = false })
    }
}

@Composable
private fun Header(context: EventContext) {
    val event = context.event
    val s = context.settlement
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(formatDate(event.date, language), event.location).joinToString(" · "),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            StatusChip(s.status, event.archived)
        }
        event.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TotalRow(stringResource(Res.string.total_income), s.income, context.currency)
                if (!s.fundAmount.isZero) TotalRow(stringResource(Res.string.total_fund), -s.fundAmount, context.currency)
                if (!s.refundsTotal.isZero) TotalRow(stringResource(Res.string.total_refunds), -s.refundsTotal, context.currency)
                TotalRow(stringResource(Res.string.total_net), s.net, context.currency, bold = true)
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: Money, currency: String, bold: Boolean = false) {
    Row {
        Text(label, Modifier.weight(1f), style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        MoneyText(amount, currency, bold = bold)
    }
}

@Composable
private fun Problems(context: EventContext) {
    val problems = context.settlement.problems
    if (problems.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            problems.forEach { problem ->
                Text(
                    when (problem) {
                        SettlementProblem.LOSS -> stringResource(
                            Res.string.problem_loss,
                            formatMoney(-context.settlement.net, context.currency, language),
                        )
                        SettlementProblem.NO_SHARES -> stringResource(Res.string.problem_no_shares)
                        SettlementProblem.NO_TREASURER -> stringResource(Res.string.problem_no_treasurer)
                    },
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
