package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.add_cost
import com.jacekgajek.artsplit.app.generated.cost_status_partly
import com.jacekgajek.artsplit.app.generated.cost_status_pending
import com.jacekgajek.artsplit.app.generated.cost_status_planned
import com.jacekgajek.artsplit.app.generated.cost_status_refunded
import com.jacekgajek.artsplit.app.generated.costs_empty
import com.jacekgajek.artsplit.app.generated.costs_outstanding_total
import com.jacekgajek.artsplit.app.generated.filter_all
import com.jacekgajek.artsplit.app.generated.tab_my_costs
import com.jacekgajek.artsplit.app.generated.unnamed_cost
import com.jacekgajek.artsplit.app.ui.components.MoneyText
import com.jacekgajek.artsplit.app.ui.components.TabTopBar
import com.jacekgajek.artsplit.app.ui.components.label
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.domain.CostLedger
import com.jacekgajek.artsplit.domain.CostStatus
import com.jacekgajek.artsplit.model.sumOfMoney
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun CostStatus.labelRes(): StringResource = when (this) {
    CostStatus.PENDING -> Res.string.cost_status_pending
    CostStatus.REFUND_PLANNED -> Res.string.cost_status_planned
    CostStatus.PARTLY_REFUNDED -> Res.string.cost_status_partly
    CostStatus.REFUNDED -> Res.string.cost_status_refunded
}

/** The current member's own costs and how much of each has been refunded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyCostsScreen(state: BandState.Ready, onOpenCost: (String?) -> Unit, onSettings: () -> Unit, onRoster: () -> Unit) {
    val band = state.band
    var filter by rememberSaveable { mutableStateOf<CostStatus?>(null) }
    val mine = band.costs.filter { it.memberId == state.me?.id }.sortedByDescending { it.date }
    val shown = mine.filter { filter == null || CostLedger.status(band, it) == filter }
    val unpaid = mine.sumOfMoney { CostLedger.unpaid(band, it) }

    Scaffold(
        topBar = { TabTopBar(stringResource(Res.string.tab_my_costs), onSettings, onRoster) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenCost(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(Res.string.add_cost)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(Res.string.costs_outstanding_total, formatMoney(unpaid, band.currency, language)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, { Text(stringResource(Res.string.filter_all)) })
                    CostStatus.entries.forEach { status ->
                        FilterChip(filter == status, { filter = status }, { Text(stringResource(status.labelRes())) })
                    }
                }
            }
            if (shown.isEmpty()) item { Text(stringResource(Res.string.costs_empty), Modifier.padding(vertical = 32.dp)) }
            items(shown, key = { it.id }) { cost ->
                Card(onClick = { onOpenCost(cost.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(formatDate(cost.date, language), cost.eventId?.let { band.event(it)?.name }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(cost.amount, band.currency, bold = true)
                            Text(stringResource(CostLedger.status(band, cost).labelRes()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
