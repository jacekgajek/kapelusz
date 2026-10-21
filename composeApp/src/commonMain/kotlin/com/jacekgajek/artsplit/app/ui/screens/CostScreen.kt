package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.data.today
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.add_cost
import com.jacekgajek.artsplit.app.generated.amount
import com.jacekgajek.artsplit.app.generated.category
import com.jacekgajek.artsplit.app.generated.cost_date
import com.jacekgajek.artsplit.app.generated.cost_event
import com.jacekgajek.artsplit.app.generated.cost_locked
import com.jacekgajek.artsplit.app.generated.cost_no_event
import com.jacekgajek.artsplit.app.generated.cost_planned_at
import com.jacekgajek.artsplit.app.generated.cost_refunded_at
import com.jacekgajek.artsplit.app.generated.cost_refunded_fund
import com.jacekgajek.artsplit.app.generated.delete
import com.jacekgajek.artsplit.app.generated.delete_cost_confirm
import com.jacekgajek.artsplit.app.generated.delete_cost_title
import com.jacekgajek.artsplit.app.generated.description_optional
import com.jacekgajek.artsplit.app.generated.edit_cost
import com.jacekgajek.artsplit.app.generated.event_not_found
import com.jacekgajek.artsplit.app.generated.paid_by
import com.jacekgajek.artsplit.app.generated.paid_by_name
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.ui.components.BackTopBar
import com.jacekgajek.artsplit.app.ui.components.ConfirmDialog
import com.jacekgajek.artsplit.app.ui.components.DateField
import com.jacekgajek.artsplit.app.ui.components.Dropdown
import com.jacekgajek.artsplit.app.ui.components.MoneyField
import com.jacekgajek.artsplit.app.ui.components.label
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.components.parseMoney
import com.jacekgajek.artsplit.app.ui.components.rememberActions
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.formatMoneyInput
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.auth.Permissions
import com.jacekgajek.artsplit.domain.CostLedger
import com.jacekgajek.artsplit.domain.CostStatuses
import com.jacekgajek.artsplit.model.Cost
import com.jacekgajek.artsplit.model.CostCategory
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.DeleteCost
import com.jacekgajek.artsplit.ops.UpdateCost
import org.jetbrains.compose.resources.stringResource

/** Fast cost form: amount first, everything else optional. [costId] `null` adds a new cost. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CostScreen(graph: AppGraph, state: BandState.Ready, costId: String?, onBack: () -> Unit) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val existing = costId?.let { band.cost(it) }
    val lang = language

    var amount by remember { mutableStateOf(existing?.let { formatMoneyInput(it.amount, lang) } ?: "") }
    var paidBy by remember { mutableStateOf(existing?.memberId ?: state.me?.id) }
    var date by remember { mutableStateOf(existing?.date ?: today()) }
    var eventId by remember { mutableStateOf(existing?.eventId) }
    var category by remember { mutableStateOf(existing?.category) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (existing == null) focus.requestFocus() }

    val actor = Actor.User(state.userId)
    val editable = existing == null || Permissions.isAllowed(band, actor, UpdateCost(existing.id))
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }
    // Leads may add costs for anyone; others only for themselves (the permission check decides).
    val payers = band.members.filter { !it.archived && Permissions.isAllowed(band, actor, AddCost("", it.id, Money(1), date)) }
    val canSave = editable && parsed != null && paidBy != null
    fun save() {
        val ok = if (existing == null) {
            actions.run(AddCost(newId(), paidBy!!, parsed!!, date, eventId, category, description.ifBlank { null }))
        } else {
            actions.run(
                UpdateCost(
                    existing.id,
                    amount = Change(parsed!!).takeIf { parsed != existing.amount },
                    date = Change(date).takeIf { date != existing.date },
                    incurredForEventId = Change(eventId).takeIf { eventId != existing.eventId },
                    category = Change(category).takeIf { category != existing.category },
                    description = Change(description.ifBlank { null }).takeIf { description.trim() != existing.description.orEmpty() },
                ),
            )
        }
        if (ok) onBack()
    }
    val events = listOf<String?>(null) + band.events.filter { !it.archived || it.id == eventId }.sortedByDescending { it.date }.map { it.id }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(if (existing == null) Res.string.add_cost else Res.string.edit_cost), onBack) {
                if (existing != null && Permissions.isAllowed(band, actor, DeleteCost(existing.id))) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(Res.string.delete)) }
                }
                TextButton(onClick = ::save, enabled = canSave) { Text(stringResource(Res.string.save)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (costId != null && existing == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(Res.string.event_not_found)) }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (existing != null) RefundInfo(state, existing)
                when {
                    existing == null && payers.size > 1 -> Dropdown(paidBy, payers.map { it.id }, { paidBy = it }, stringResource(Res.string.paid_by)) { id ->
                        id?.let { band.member(it)?.name }.orEmpty()
                    }
                    existing != null && existing.memberId != state.me?.id -> Text(
                        stringResource(Res.string.paid_by_name, band.member(existing.memberId)?.name.orEmpty()),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (!editable) Text(stringResource(Res.string.cost_locked), color = MaterialTheme.colorScheme.error)
                MoneyField(amount, { if (editable) amount = it }, stringResource(Res.string.amount), Modifier.focusRequester(focus))
                DateField(date, { if (editable) date = it }, stringResource(Res.string.cost_date))
                Dropdown(eventId, events, { if (editable) eventId = it }, stringResource(Res.string.cost_event)) { id ->
                    id?.let { band.event(it) }?.let { "${it.name} · ${formatDate(it.date, lang)}" } ?: stringResource(Res.string.cost_no_event)
                }
                Text(stringResource(Res.string.category), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CostCategory.entries.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { if (editable) category = if (category == option) null else option },
                            label = { Text(option.label()) },
                        )
                    }
                }
                OutlinedTextField(
                    description,
                    { if (editable) description = it },
                    label = { Text(stringResource(Res.string.description_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(Res.string.delete_cost_title),
            text = stringResource(Res.string.delete_cost_confirm),
            confirmLabel = stringResource(Res.string.delete),
            onConfirm = { if (actions.run(DeleteCost(existing.id))) onBack() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Status of the cost and where it was refunded. */
@Composable
private fun RefundInfo(state: BandState.Ready, cost: Cost) {
    val band = state.band
    val lang = language
    val statuses = remember(band) { CostStatuses(band) }
    val refundedAt = band.events.mapNotNull { event -> event.refundOf(cost.id)?.let { Triple(event, it.amount, statuses.isSettled(event)) } }
    val fundRefund = band.fundEntries.filter { it.costId == cost.id }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(stringResource(statuses.status(cost).labelRes()), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatMoney(statuses.paid(cost.id), band.currency, lang)} / ${formatMoney(cost.amount, band.currency, lang)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            refundedAt.forEach { (event, amount, paid) ->
                Text(
                    stringResource(
                        if (paid) Res.string.cost_refunded_at else Res.string.cost_planned_at,
                        formatMoney(amount, band.currency, lang),
                        event.name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            fundRefund.forEach {
                Text(stringResource(Res.string.cost_refunded_fund, formatMoney(-it.amount, band.currency, lang)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
