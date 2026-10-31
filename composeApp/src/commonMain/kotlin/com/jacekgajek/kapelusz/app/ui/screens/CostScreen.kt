package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.category
import com.jacekgajek.kapelusz.app.generated.cost_date
import com.jacekgajek.kapelusz.app.generated.cost_event
import com.jacekgajek.kapelusz.app.generated.cost_locked
import com.jacekgajek.kapelusz.app.generated.cost_no_event
import com.jacekgajek.kapelusz.app.generated.cost_not_found
import com.jacekgajek.kapelusz.app.generated.cost_planned_at
import com.jacekgajek.kapelusz.app.generated.cost_refunded_at
import com.jacekgajek.kapelusz.app.generated.cost_refunded_fund
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.delete_cost_confirm
import com.jacekgajek.kapelusz.app.generated.delete_cost_title
import com.jacekgajek.kapelusz.app.generated.description_optional
import com.jacekgajek.kapelusz.app.generated.edit_cost
import com.jacekgajek.kapelusz.app.generated.open_event
import com.jacekgajek.kapelusz.app.generated.paid_by
import com.jacekgajek.kapelusz.app.generated.paid_by_name
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.search_by_title
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.CostNav
import com.jacekgajek.kapelusz.app.ui.CostRoute
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.EmptyState
import com.jacekgajek.kapelusz.app.ui.components.MAX_LIST_WIDTH
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.icon
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import org.jetbrains.compose.resources.stringResource

/** Fast cost form: amount first, everything else optional. The form itself is [CostViewModel]; this draws it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CostScreen(session: BandSession, nav: CostNav, form: CostViewModel) {
    val state = session.state
    val band = state.band
    val messages = rememberMessages()
    val actions = rememberActions(session.changes, messages)
    val s = form.state
    val existing = form.existing
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (s.isNew) focus.requestFocus() }
    // A single choice is made already (the picker is shown only for several).
    LaunchedEffect(s.payers.size) { form.takeOnlyPayer() }
    LaunchedEffect(s.events) { form.pruneEvent() }

    fun finish(outcome: CostOutcome?) = when (outcome) {
        null -> Unit

        is CostOutcome.Refused -> actions.show(outcome.rejection)

        is CostOutcome.Added -> {
            if (outcome.forOther) nav.addedForOther()
            nav.added(outcome.costId)
            nav.back()
        }

        CostOutcome.Updated, CostOutcome.Deleted -> nav.back()
    }

    AppScaffold(
        messages,
        topBar = {
            BackTopBar(stringResource(if (s.isNew) Res.string.add_cost else Res.string.edit_cost), nav::back) {
                if (s.canDelete) IconButton(onClick = form::askDelete) { Icon(Icons.Default.Delete, stringResource(Res.string.delete)) }
                TextButton(onClick = { finish(form.save()) }, enabled = s.canSave) { Text(stringResource(Res.string.save)) }
            }
        },
    ) { padding ->
        if (s.notFound) {
            EmptyState(stringResource(Res.string.cost_not_found), Modifier.padding(padding))
            return@AppScaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = MAX_LIST_WIDTH).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (existing != null) RefundInfo(state, existing, nav::openRefunds)
                PayerField(state, existing, form.draft.payerId, s.payers, form::payer)
                if (!s.editable) Text(stringResource(Res.string.cost_locked), color = MaterialTheme.colorScheme.error)
                MoneyField(form.draft.amountText, form::amount, stringResource(Res.string.amount), Modifier.focusRequester(focus))
                DateField(form.draft.date, form::date, stringResource(Res.string.cost_date))
                EventField(state, s.forEvent, form.draft.eventId, s.events, form::event)
                Text(stringResource(Res.string.category), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CostCategory.entries.forEach { option ->
                        FilterChip(
                            selected = form.draft.category == option,
                            onClick = { form.category(option) },
                            label = { Text(option.label()) },
                            // Kept when selected (the chip's fill says so): the same icon as in the lists (ITERATION_6 §4).
                            leadingIcon = { Icon(option.icon(), null, Modifier.size(FilterChipDefaults.IconSize)) },
                        )
                    }
                }
                OutlinedTextField(
                    form.draft.description,
                    form::description,
                    label = { Text(stringResource(Res.string.description_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentsSection(
                    session.attachments,
                    owner = existing?.let { AttachmentOwner.OfCost(it.id) },
                    attachments = existing?.attachments.orEmpty(),
                    drafts = form.drafts,
                    onDrafts = { form.drafts = it },
                    editable = s.attachmentsEditable,
                    removable = s.attachmentsRemovable,
                    actions = actions,
                    undoRemoval = true,
                )
            }
        }
    }

    when (form.dialog) {
        CostDialog.CONFIRM_DELETE -> ConfirmDialog(
            title = stringResource(Res.string.delete_cost_title),
            text = stringResource(Res.string.delete_cost_confirm),
            confirmLabel = stringResource(Res.string.delete),
            onConfirm = { finish(form.delete()) },
            onDismiss = form::dismissDialog,
        )

        null -> Unit
    }
}

/** The screen's [CostViewModel], scoped to its back-stack entry: the band's services it needs, and the typed amount's language. */
internal fun costViewModel(session: BandSession, route: CostRoute, language: String): CreationExtras.() -> CostViewModel = {
    // Photos or PDFs shared to Kapelusz for a new cost of this event (ITERATION_6 §1): taken once, when the form opens.
    val sharedTo = route.forEventId?.takeIf { route.costId == null }
    CostViewModel(
        route,
        session.bands.state,
        dispatch = { session.changes.dispatch(it) },
        attachAfterSave = { costId, files -> session.attachments.attachAfterSave(costId, files) },
        sharedFiles = sharedTo?.let { session.incoming.takeSharedFiles(it, toCost = true) }.orEmpty(),
        handle = createSavedStateHandle(),
        today = today(),
        amountText = { formatMoneyInput(it, language) },
    )
}

/** Who paid: a picker for a lead adding one, the one possible payer shown otherwise, the payer's name on someone else's cost. */
@Composable
private fun PayerField(state: BandState.Ready, existing: Cost?, payerId: String?, payers: List<Member>, onPayer: (String?) -> Unit) {
    val band = state.band
    when {
        existing == null && payers.size > 1 -> {
            val label = stringResource(Res.string.paid_by)
            Dropdown(payerId, payers.map { it.id }, onPayer, label) { id -> id?.let { band.member(it)?.name }.orEmpty() }
        }

        // A single possible payer (always the case for members) is shown, not chosen, like a fixed event.
        existing == null -> OutlinedTextField(
            payerId?.let { band.member(it)?.name }.orEmpty(),
            {},
            readOnly = true,
            label = { Text(stringResource(Res.string.paid_by)) },
            modifier = Modifier.fillMaxWidth(),
        )

        existing.memberId != state.me?.id -> Text(
            stringResource(Res.string.paid_by_name, band.member(existing.memberId)?.name.orEmpty()),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** The event the cost is for: fixed when opened from one, else a choice among the payer's. */
@Composable
private fun EventField(state: BandState.Ready, forEvent: Event?, eventId: String?, events: List<String?>, onEvent: (String?) -> Unit) {
    val lang = language
    if (forEvent != null) {
        OutlinedTextField(
            "${forEvent.name} · ${formatDate(forEvent.date, lang)}",
            {},
            readOnly = true,
            label = { Text(stringResource(Res.string.cost_event)) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        val none = stringResource(Res.string.cost_no_event)
        Dropdown(eventId, events, onEvent, stringResource(Res.string.cost_event), searchHint = Res.string.search_by_title) { id ->
            id?.let { state.band.event(it) }?.let { "${it.name} · ${formatDate(it.date, lang)}" } ?: none
        }
    }
}

/** Status of the cost and where it was refunded; a tap on an event opens it. */
@Composable
private fun RefundInfo(state: BandState.Ready, cost: Cost, onOpenEvent: (String) -> Unit) {
    val band = state.band
    val lang = language
    val statuses = state.view.costStatuses
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
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(Res.string.open_event)) { onOpenEvent(event.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (paid) Res.string.cost_refunded_at else Res.string.cost_planned_at,
                            formatMoney(amount, band.currency, lang),
                            event.name,
                        ),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            fundRefund.forEach {
                Text(stringResource(Res.string.cost_refunded_fund, formatMoney(-it.amount, band.currency, lang)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
