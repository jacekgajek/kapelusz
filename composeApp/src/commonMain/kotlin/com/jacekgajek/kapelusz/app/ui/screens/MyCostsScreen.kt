package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.cost_approved_part
import com.jacekgajek.kapelusz.app.generated.cost_status_partly
import com.jacekgajek.kapelusz.app.generated.cost_status_pending
import com.jacekgajek.kapelusz.app.generated.cost_status_refunded
import com.jacekgajek.kapelusz.app.generated.costs_empty
import com.jacekgajek.kapelusz.app.generated.costs_none
import com.jacekgajek.kapelusz.app.generated.costs_none_everyone
import com.jacekgajek.kapelusz.app.generated.costs_outstanding_total
import com.jacekgajek.kapelusz.app.generated.costs_outstanding_total_everyone
import com.jacekgajek.kapelusz.app.generated.filter_all
import com.jacekgajek.kapelusz.app.generated.filter_all_members
import com.jacekgajek.kapelusz.app.generated.filter_mine
import com.jacekgajek.kapelusz.app.generated.filter_pending
import com.jacekgajek.kapelusz.app.generated.filter_refunded
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.CostsNav
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.Choice
import com.jacekgajek.kapelusz.app.ui.components.CostRow
import com.jacekgajek.kapelusz.app.ui.components.SegmentedChoice
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.sumOfMoney
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.settlement.CostStatus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun CostStatus.labelRes(): StringResource = when (this) {
    // Not included anywhere yet, or included in an event not settled yet: either way the owner waits (ITERATION_4 §1.2).
    CostStatus.PENDING, CostStatus.REFUND_PLANNED -> Res.string.cost_status_pending

    CostStatus.PARTLY_REFUNDED -> Res.string.cost_status_partly

    CostStatus.REFUNDED -> Res.string.cost_status_refunded
}

/**
 * A cost's status as its owner reads it. Part of it approved but not paid yet still waits, with how much was approved:
 * "Waiting · approved 100 zł" (ITERATION_4 §1.2).
 */
@Composable
fun costStatusText(band: Band, cost: Cost, status: CostStatus): String {
    val label = stringResource(status.labelRes())
    val approved = CostLedger.refunded(band, cost.id)
    if (status != CostStatus.REFUND_PLANNED || approved >= cost.amount) return label
    return "$label · " + stringResource(Res.string.cost_approved_part, formatMoney(approved, band.currency, language))
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
fun MyCostsScreen(session: BandSession, everyone: Boolean, onEveryone: (Boolean) -> Unit, nav: CostsNav) {
    val state = session.state
    val onOpenCost: (String?) -> Unit = { if (it == null) nav.addCost() else nav.openCost(it) }
    val band = state.band
    val messages = rememberMessages()
    val can = session.can
    var filter by rememberSaveable { mutableStateOf(CostFilter.PENDING) }
    val isLead = state.me?.role?.atLeast(Role.LEAD) == true
    // A viewer sees everything, costs included, and has none of their own to switch to.
    val viewer = state.me?.role == Role.VIEWER
    val showAll = viewer || (isLead && everyone)
    val mine = band.costs.filter { showAll || it.memberId == state.me?.id }.sortedByDescending { it.date }
    val statuses = state.view.costStatuses
    val shown = mine.filter { filter.matches(statuses.status(it)) }
    val unpaid = mine.sumOfMoney { statuses.unpaid(it) }

    AppScaffold(
        messages,
        topBar = { TabTopBar(stringResource(Res.string.tab_my_costs)) },
        floatingActionButton = {
            if (can.addCost()) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenCost(null) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(Res.string.add_cost)) },
                )
            }
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
                    val filters = listOf(
                        Choice(false, stringResource(Res.string.filter_mine)),
                        Choice(true, stringResource(Res.string.filter_all_members)),
                    )
                    SegmentedChoice(filters, everyone, onEveryone, Modifier.widthIn(max = 360.dp))
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
                val subtitle = listOfNotNull(
                    band.member(cost.memberId)?.name?.takeIf { showAll },
                    formatDate(cost.date, language),
                    cost.eventId?.let { band.event(it)?.name },
                ).joinToString(" · ")
                Card(onClick = { onOpenCost(cost.id) }, modifier = Modifier.fillMaxWidth()) {
                    CostRow(cost, subtitle, Modifier.padding(16.dp), status = costStatusText(band, cost, status), bold = true)
                }
            }
        }
    }
}
