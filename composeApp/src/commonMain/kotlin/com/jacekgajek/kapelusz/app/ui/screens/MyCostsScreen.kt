package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.cost_status_partly
import com.jacekgajek.kapelusz.app.generated.cost_status_pending
import com.jacekgajek.kapelusz.app.generated.cost_status_refunded
import com.jacekgajek.kapelusz.app.generated.cost_status_refused
import com.jacekgajek.kapelusz.app.generated.costs_empty
import com.jacekgajek.kapelusz.app.generated.costs_none
import com.jacekgajek.kapelusz.app.generated.costs_none_everyone
import com.jacekgajek.kapelusz.app.generated.costs_outstanding_total
import com.jacekgajek.kapelusz.app.generated.costs_outstanding_total_everyone
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.filter_all
import com.jacekgajek.kapelusz.app.generated.filter_all_members
import com.jacekgajek.kapelusz.app.generated.filter_mine
import com.jacekgajek.kapelusz.app.generated.filter_pending
import com.jacekgajek.kapelusz.app.generated.filter_refunded
import com.jacekgajek.kapelusz.app.generated.refused_cost_nothing
import com.jacekgajek.kapelusz.app.generated.refused_cost_resubmit
import com.jacekgajek.kapelusz.app.generated.refused_cost_text
import com.jacekgajek.kapelusz.app.generated.refused_cost_title
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.domain.CostStatuses
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.sumOfMoney
import com.jacekgajek.kapelusz.ops.DeleteCost
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun CostStatus.labelRes(): StringResource = when (this) {
    // Not included anywhere yet, or included in an event not settled yet: either way the owner waits (ITERATION_4 §1.2).
    CostStatus.PENDING, CostStatus.REFUND_PLANNED -> Res.string.cost_status_pending
    CostStatus.REFUSED -> Res.string.cost_status_refused
    CostStatus.PARTLY_REFUNDED -> Res.string.cost_status_partly
    CostStatus.REFUNDED -> Res.string.cost_status_refunded
}

/** Coarse refund filter: anything not fully refunded yet counts as pending. */
private enum class CostFilter(val label: StringResource) {
    PENDING(Res.string.filter_pending),
    REFUNDED(Res.string.filter_refunded),
    ALL(Res.string.filter_all),
    ;

    fun matches(status: CostStatus) = when (this) {
        PENDING -> status != CostStatus.REFUNDED
        REFUNDED -> status == CostStatus.REFUNDED
        ALL -> true
    }
}

/**
 * The current member's own costs and how much of each has been refunded. Leads can switch to everyone's costs with
 * [everyone]; the flag is owned by the caller so adding a cost for someone else can switch to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyCostsScreen(
    graph: AppGraph,
    state: BandState.Ready,
    everyone: Boolean,
    onEveryone: (Boolean) -> Unit,
    onOpenCost: (String?) -> Unit,
    onSettings: () -> Unit,
    onRoster: () -> Unit,
) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val can = Can(band, state.userId)
    var asking by remember { mutableStateOf<Cost?>(null) }
    var filter by rememberSaveable { mutableStateOf(CostFilter.PENDING) }
    val isLead = state.me?.role?.atLeast(Role.LEAD) == true
    val showAll = isLead && everyone
    val mine = band.costs.filter { showAll || it.memberId == state.me?.id }.sortedByDescending { it.date }
    val statuses = remember(band) { CostStatuses(band) }
    val shown = mine.filter { filter.matches(statuses.status(it)) }
    val unpaid = mine.sumOfMoney { statuses.unpaid(it) }

    Scaffold(
        topBar = { TabTopBar(stringResource(Res.string.tab_my_costs), onSettings, onRoster) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (can.addCost()) ExtendedFloatingActionButton(
                onClick = { onOpenCost(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(Res.string.add_cost)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(
                        if (showAll) Res.string.costs_outstanding_total_everyone else Res.string.costs_outstanding_total,
                        formatMoney(unpaid, band.currency, language),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (isLead) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                        listOf(false to Res.string.filter_mine, true to Res.string.filter_all_members).forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = everyone == value,
                                onClick = { onEveryone(value) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CostFilter.entries.forEach { option ->
                        FilterChip(filter == option, { filter = option }, { Text(stringResource(option.label)) })
                    }
                }
            }
            if (shown.isEmpty()) {
                val text = when {
                    mine.isNotEmpty() -> Res.string.costs_empty
                    showAll -> Res.string.costs_none_everyone
                    else -> Res.string.costs_none
                }
                item { Text(stringResource(text), Modifier.padding(vertical = 32.dp)) }
            }
            items(shown, key = { it.id }) { cost ->
                val status = statuses.status(cost)
                val ownRefused = status == CostStatus.REFUSED && cost.memberId == state.me?.id
                Card(onClick = { if (ownRefused) asking = cost else onOpenCost(cost.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost), style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(
                                    band.member(cost.memberId)?.name?.takeIf { showAll },
                                    formatDate(cost.date, language),
                                    cost.eventId?.let { band.event(it)?.name },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(cost.amount, band.currency, bold = true)
                            Text(
                                stringResource(status.labelRes()),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status == CostStatus.REFUSED) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        }
                        // A refused cost goes with one tap (ITERATION_4 §1.2), as asked: no confirmation.
                        if (ownRefused && can.allowed(DeleteCost(cost.id))) {
                            IconButton(onClick = { actions.run(DeleteCost(cost.id)) }) { Icon(Icons.Default.Delete, stringResource(Res.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    asking?.let { cost -> RefusedCostDialog(cost, actions, onEdit = { onOpenCost(cost.id) }, onDismiss = { asking = null }) }
}

/**
 * The owner opening a refused cost is asked first (ITERATION_4 §1.2): edit it (saving resubmits it to the leads), delete
 * it, or leave it.
 */
@Composable
fun RefusedCostDialog(cost: Cost, actions: Actions, onEdit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.refused_cost_title)) },
        text = { Text(stringResource(Res.string.refused_cost_text)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onDismiss(); onEdit() }) { Text(stringResource(Res.string.refused_cost_resubmit)) }
                TextButton(onClick = { onDismiss(); actions.run(DeleteCost(cost.id)) }) { Text(stringResource(Res.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.refused_cost_nothing)) }
            }
        },
    )
}
