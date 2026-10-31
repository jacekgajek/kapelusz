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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.newId
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
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.MessageHost
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.icon
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.view.CostChoices
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** Fast cost form: amount first, everything else optional. [costId] `null` adds a new cost. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CostScreen(session: BandSession, route: CostRoute, nav: CostNav) {
    val state = session.state
    val changes = session.changes
    val incoming = session.incoming
    val fileStore = session.attachments
    val costId = route.costId
    val forEventId = route.forEventId
    val onBack: () -> Unit = nav::back
    val onAddedForOther: () -> Unit = nav::addedForOther
    val onAdded: (String) -> Unit = nav::added
    val onOpenEvent: (String) -> Unit = nav::openRefunds
    val band = state.band
    val messages = rememberMessages()
    val actions = rememberActions(changes, messages)
    val existing = costId?.let { band.cost(it) }
    val lang = language

    // The cost as it was when the form opened: Save sends only what the user changed since, so a change someone else
    // made meanwhile is not overwritten (ITERATION_3 R8). Saved with the typed text, or after a rotation an untouched
    // old amount would count as changed against a newer snapshot.
    val openedJson = rememberSaveable { existing?.let { KapeluszJson.encodeToString(Cost.serializer(), it) }.orEmpty() }
    val opened = remember(openedJson) { openedJson.takeIf { it.isNotEmpty() }?.let { KapeluszJson.decodeFromString(Cost.serializer(), it) } }
    // Typed text survives rotation and the camera app's round trip (ITERATION_3 R8).
    var amount by rememberSaveable { mutableStateOf(existing?.let { formatMoneyInput(it.amount, lang) } ?: "") }
    // Opened from an event: the new cost is for that event (fixed; My costs books it elsewhere), so only its
    // participants can have paid it.
    val forEvent = forEventId?.takeIf { existing == null }?.let { band.event(it) }
    // All of the form survives a trip to an event and back (a refund's event line opens it), as the typed text does.
    var paidBy by rememberSaveable { mutableStateOf(existing?.memberId ?: state.me?.id?.takeIf { forEvent == null || forEvent.participant(it) != null }) }
    var date by rememberSaveable(stateSaver = DateSaver) { mutableStateOf(existing?.date ?: today()) }
    var eventId by rememberSaveable { mutableStateOf(existing?.eventId ?: forEventId?.takeIf { band.event(it) != null }) }
    var category by rememberSaveable { mutableStateOf(existing?.category) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    // Photos or PDFs shared to Kapelusz for a new cost of this event (ITERATION_6 §1).
    var drafts by remember { mutableStateOf(if (existing == null && forEventId != null) incoming.takeSharedFiles(forEventId, toCost = true) else listOf()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (existing == null) focus.requestFocus() }

    val can = session.can
    val choices = remember(state) { CostChoices(state.view, can) }
    val editable = existing == null || can.editCost(existing)
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }
    // Leads may add costs for anyone; others only for themselves (the permission check decides).
    val payers = choices.payers(forEvent)
    // A single choice is made already (the picker is shown only for several).
    LaunchedEffect(payers.size) { if (existing == null && paidBy == null) payers.singleOrNull()?.let { paidBy = it.id } }
    val canSave = editable && parsed != null && paidBy != null && !saved
    fun save() {
        // Once only: the screen stays tappable while it fades out, and a second tap would add the cost again.
        if (saved) return
        val ok = if (existing == null) {
            val id = newId()
            actions.run(AddCost(id, paidBy!!, parsed!!, date, eventId, category, description.ifBlank { null }))
                .also { if (it) fileStore.attachAfterSave(id, drafts) }
                .also { if (it && paidBy != state.me?.id) onAddedForOther() }
                .also { if (it) onAdded(id) }
        } else {
            actions.run(
                UpdateCost(
                    existing.id,
                    amount = Change(parsed!!).takeIf { parsed != (opened ?: existing).amount },
                    date = Change(date).takeIf { date != (opened ?: existing).date },
                    incurredForEventId = Change(eventId).takeIf { eventId != (opened ?: existing).eventId },
                    category = Change(category).takeIf { category != (opened ?: existing).category },
                    description = Change(description.ifBlank { null }).takeIf { description.trim() != (opened ?: existing).description.orEmpty() },
                ),
            )
        }
        if (ok) {
            saved = true
            onBack()
        }
    }
    val events = choices.events(existing, paidBy, eventId)
    // Another payer: an event they are not in is no longer a choice.
    LaunchedEffect(events) { if (forEvent == null && eventId !in events) eventId = null }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(if (existing == null) Res.string.add_cost else Res.string.edit_cost), onBack) {
                if (existing != null && can.deleteCost(existing)) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, stringResource(Res.string.delete)) }
                }
                TextButton(onClick = ::save, enabled = canSave) { Text(stringResource(Res.string.save)) }
            }
        },
        snackbarHost = { MessageHost(messages) },
    ) { padding ->
        if (costId != null && existing == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(Res.string.cost_not_found)) }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (existing != null) RefundInfo(state, existing, onOpenEvent)
                when {
                    existing == null && payers.size > 1 -> Dropdown(paidBy, payers.map { it.id }, { paidBy = it }, stringResource(Res.string.paid_by)) { id ->
                        id?.let { band.member(it)?.name }.orEmpty()
                    }

                    // A single possible payer (always the case for members) is shown, not chosen, like a fixed event.
                    existing == null -> OutlinedTextField(
                        paidBy?.let { band.member(it)?.name }.orEmpty(),
                        {},
                        readOnly = true,
                        label = { Text(stringResource(Res.string.paid_by)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    existing != null && existing.memberId != state.me?.id -> Text(
                        stringResource(Res.string.paid_by_name, band.member(existing.memberId)?.name.orEmpty()),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (!editable) Text(stringResource(Res.string.cost_locked), color = MaterialTheme.colorScheme.error)
                MoneyField(amount, { if (editable) amount = it }, stringResource(Res.string.amount), Modifier.focusRequester(focus))
                DateField(date, { if (editable) date = it }, stringResource(Res.string.cost_date))
                if (forEvent != null) {
                    OutlinedTextField(
                        "${forEvent.name} · ${formatDate(forEvent.date, lang)}",
                        {},
                        readOnly = true,
                        label = { Text(stringResource(Res.string.cost_event)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Dropdown(eventId, events, { if (editable) eventId = it }, stringResource(Res.string.cost_event), searchHint = Res.string.search_by_title) { id ->
                        id?.let { band.event(it) }?.let { "${it.name} · ${formatDate(it.date, lang)}" } ?: stringResource(Res.string.cost_no_event)
                    }
                }
                Text(stringResource(Res.string.category), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CostCategory.entries.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { if (editable) category = if (category == option) null else option },
                            label = { Text(option.label()) },
                            // Kept when selected (the chip's fill says so): the same icon as in the lists (ITERATION_6 §4).
                            leadingIcon = { Icon(option.icon(), null, Modifier.size(FilterChipDefaults.IconSize)) },
                        )
                    }
                }
                OutlinedTextField(
                    description,
                    { if (editable) description = it },
                    label = { Text(stringResource(Res.string.description_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentsSection(
                    fileStore,
                    owner = existing?.let { AttachmentOwner.OfCost(it.id) },
                    attachments = existing?.attachments.orEmpty(),
                    drafts = drafts,
                    onDrafts = { drafts = it },
                    editable = existing == null || can.attachTo(AttachmentOwner.OfCost(existing.id)),
                    removable = existing == null || can.detachFrom(AttachmentOwner.OfCost(existing.id)),
                    actions = actions,
                    undoRemoval = true,
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

private val DateSaver = Saver<LocalDate, String>({ it.toString() }, { LocalDate.parse(it) })
