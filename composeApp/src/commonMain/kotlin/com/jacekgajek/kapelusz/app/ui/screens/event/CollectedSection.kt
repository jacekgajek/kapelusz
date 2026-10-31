package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_awaited
import com.jacekgajek.kapelusz.app.generated.add_money
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.attachment_hint_holding
import com.jacekgajek.kapelusz.app.generated.awaited_payment
import com.jacekgajek.kapelusz.app.generated.awaited_welched
import com.jacekgajek.kapelusz.app.generated.awaited_welched_text
import com.jacekgajek.kapelusz.app.generated.awaited_welched_title
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.collected_by
import com.jacekgajek.kapelusz.app.generated.collected_empty
import com.jacekgajek.kapelusz.app.generated.collected_money
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.delete_entry_title
import com.jacekgajek.kapelusz.app.generated.delete_files_too
import com.jacekgajek.kapelusz.app.generated.note_optional
import com.jacekgajek.kapelusz.app.generated.organiser
import com.jacekgajek.kapelusz.app.generated.receive_awaited
import com.jacekgajek.kapelusz.app.generated.receive_awaited_hint
import com.jacekgajek.kapelusz.app.generated.receive_awaited_title
import com.jacekgajek.kapelusz.app.generated.received_by
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.source
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.components.resolved
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.format.formatMoneyInput
import com.jacekgajek.kapelusz.app.ui.screens.AttachmentsSection
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

/** "Where the money sits": who collected how much, from which source. */
@Composable
fun CollectedSection(context: EventContext) {
    val event = context.event
    var dialog by remember { mutableStateOf<CollectedDialog?>(null) }
    val allowedParties = context.can.holdingParties(event)
    val receiveParties = context.can.receiveParties(event)
    val organiser = stringResource(Res.string.organiser)
    // A fee paid straight to the band's account is held by the fund.
    val fund = stringResource(Res.string.band_fund)

    SectionTitle(stringResource(Res.string.collected_money))
    if (event.holdings.isEmpty()) Text(stringResource(Res.string.collected_empty), style = MaterialTheme.typography.bodyMedium)
    event.holdings.forEach { holding ->
        val edit = { dialog = CollectedDialog.Editing(holding) }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = context.can.editHolding(event, holding), onClick = edit).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (holding.awaited) organiser else partyName(context.band, event, holding.partyId, fund))
                Text(
                    listOfNotNull(holding.source.label(), holding.note, stringResource(Res.string.awaited_payment).takeIf { holding.awaited }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (holding.awaited) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
            }
            MoneyText(holding.amount)
            if (context.can.editHolding(event, holding)) EditButton(onClick = edit)
        }
        // A fee the organiser still owes (ITERATION_4 §2.2): paid (whoever got it takes it over), or never paid (it goes).
        // Laid out like Refund here under a waiting cost.
        if (holding.awaited && receiveParties.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (context.can.editHolding(event, holding)) {
                    TextButton(onClick = { dialog = CollectedDialog.Welching(holding) }) {
                        Text(stringResource(Res.string.awaited_welched), color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(onClick = { dialog = CollectedDialog.Receiving(holding) }) {
                    Text(stringResource(Res.string.receive_awaited))
                }
            }
        }
        // Its files (e.g. the contract, ITERATION_4 §2.1), for everyone who sees the money to open; changed in its dialog.
        if (holding.attachments.isNotEmpty()) {
            AttachmentsSection(
                context.fileStore,
                AttachmentOwner.OfHolding(event.id, holding.id),
                holding.attachments,
                drafts = emptyList(),
                onDrafts = {},
                editable = false,
                actions = context.actions,
                title = null,
            )
        }
    }
    if (allowedParties.isNotEmpty()) {
        TextButton(onClick = { dialog = CollectedDialog.Adding() }) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(Res.string.add_money), Modifier.padding(start = 8.dp))
        }
    }
    if (context.can.recordAwaited(event)) {
        TextButton(onClick = { dialog = CollectedDialog.AddingAwaited }) {
            Icon(Icons.Default.Schedule, null)
            Text(stringResource(Res.string.add_awaited), Modifier.padding(start = 8.dp))
        }
    }
    // Photos or PDFs shared to Kapelusz for new received money here (ITERATION_6 §1): the new entry's dialog, with them.
    val handover by context.incoming.sharedHandover.collectAsState()
    // Only on the screen in front: one on its way out (the same event opened again) would take them along.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(handover, resumed) {
        if (handover?.let { it.eventId == event.id && !it.toCost } != true || !resumed) return@LaunchedEffect
        // One dialog at a time: the new entry's.
        dialog = CollectedDialog.Adding(context.incoming.takeSharedFiles(event.id, toCost = false))
    }
    val close = { dialog = null }
    when (val open = dialog) {
        CollectedDialog.AddingAwaited -> HoldingDialog(context, initial = null, ORGANISER, listOf(ORGANISER), onDismiss = close)

        is CollectedDialog.Welching -> {
            // As it is now: part of it may have come in meanwhile, on another phone.
            val holding = resolved(event.holdings.firstOrNull { it.id == open.holding.id }, close)
            if (holding != null) WelchedDialog(context, holding, onDismiss = close)
        }

        is CollectedDialog.Receiving -> {
            // Received meanwhile (e.g. on another phone): nothing left to receive.
            val current = resolved(event.holdings.firstOrNull { it.id == open.holding.id }?.takeIf { receiveParties.isNotEmpty() }, close)
            if (current != null) ReceiveDialog(context, current, receiveParties, onDismiss = close)
        }

        is CollectedDialog.Adding -> {
            // A sync may have taken away whom the user can record for (removed from the event, a role changed): close.
            val defaultParty = resolved(context.me?.id?.takeIf { it in allowedParties } ?: allowedParties.firstOrNull(), close)
            // Files shared while it was open already: it starts again with them, rather than taking only what still fits.
            if (defaultParty != null) {
                key(open.drafts) {
                    HoldingDialog(context, initial = null, defaultParty, allowedParties, onDismiss = close, initialDrafts = open.drafts)
                }
            }
        }

        is CollectedDialog.Editing -> HoldingDialog(context, open.holding, open.holding.partyId, allowedParties, onDismiss = close)

        null -> Unit
    }
}

/** What is open over the collected money: one at a time. */
private sealed interface CollectedDialog {
    /** A new entry; [drafts]: files shared to the app for it. */
    data class Adding(val drafts: List<PreparedFile> = emptyList()) : CollectedDialog
    data object AddingAwaited : CollectedDialog
    data class Editing(val holding: Holding) : CollectedDialog
    data class Receiving(val holding: Holding) : CollectedDialog
    data class Welching(val holding: Holding) : CollectedDialog
}

/** An awaited fee that will never come (ITERATION_4 §2.2): asked first, as its files (the contract) go with it. */
@Composable
private fun WelchedDialog(context: EventContext, holding: Holding, onDismiss: () -> Unit) {
    val files = holding.attachments.size
    ConfirmDialog(
        title = stringResource(Res.string.awaited_welched_title),
        text = stringResource(Res.string.awaited_welched_text, formatMoney(holding.amount, context.currency, language)) +
            // Its files (the contract) go with it, for good.
            files.takeIf { it > 0 }?.let { " " + stringResource(Res.string.delete_files_too, it) }.orEmpty(),
        confirmLabel = stringResource(Res.string.awaited_welched),
        onConfirm = { context.actions.run(RemoveHolding(context.event.id, holding.id)) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun HoldingDialog(
    context: EventContext,
    initial: Holding?,
    defaultParty: String,
    parties: List<String>,
    onDismiss: () -> Unit,
    initialDrafts: List<PreparedFile> = emptyList(),
) {
    val event = context.event
    // Gone meanwhile (removed, or an awaited fee received on another phone): nothing left to edit (V5-M1 C4d).
    if (initial != null) resolved(event.holdings.firstOrNull { it.id == initial.id }, onDismiss) ?: return
    val lang = language
    var partyId by remember { mutableStateOf(defaultParty) }
    var amount by remember { mutableStateOf(initial?.let { formatMoneyInput(it.amount, lang) } ?: "") }
    var source by remember { mutableStateOf(initial?.source ?: if (defaultParty == ORGANISER) IncomeSource.CONTRACT else IncomeSource.DOOR) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    // Files picked before a new entry is saved; attached right after it (ITERATION_4 §2.1).
    var drafts by remember { mutableStateOf(initialDrafts) }
    var confirmDelete by remember { mutableStateOf(false) }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }
    // The entry as it is now (a file added in the dialog shows at once).
    val current = initial?.let { h -> event.holdings.firstOrNull { it.id == h.id } } ?: initial

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.collected_money)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null && partyId != ORGANISER) {
                    Dropdown(partyId, parties, { partyId = it }, stringResource(Res.string.collected_by)) {
                        partyName(context.band, event, it, "")
                    }
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Dropdown(source, IncomeSource.entries, { source = it }, stringResource(Res.string.source)) { it.label() }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.note_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                AttachmentsSection(
                    context.fileStore,
                    owner = current?.let { AttachmentOwner.OfHolding(event.id, it.id) },
                    attachments = current?.attachments.orEmpty(),
                    drafts = drafts,
                    onDrafts = { drafts = it },
                    editable = true,
                    actions = context.actions,
                    hint = Res.string.attachment_hint_holding,
                )
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                val ok = if (initial == null) {
                    val id = newId()
                    context.actions.run(AddHolding(event.id, id, partyId, parsed!!, source, note.ifBlank { null }))
                        .also { if (it) context.fileStore.attachAfterSave(AttachmentOwner.OfHolding(event.id, id), drafts) }
                } else {
                    context.actions.run(
                        UpdateHolding(
                            event.id,
                            initial.id,
                            amount = Change(parsed!!).takeIf { parsed != initial.amount },
                            source = Change(source).takeIf { source != initial.source },
                            note = Change(note.ifBlank { null }).takeIf { note.trim() != initial.note.orEmpty() },
                        ),
                    )
                }
                if (ok) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    // Its files go with it, for good (no undo), so that is asked first.
                    TextButton(onClick = {
                        if (current?.attachments.isNullOrEmpty()) {
                            if (context.actions.run(RemoveHolding(event.id, initial.id))) onDismiss()
                        } else {
                            confirmDelete = true
                        }
                    }) {
                        Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
    if (confirmDelete && initial != null) {
        ConfirmDialog(
            title = stringResource(Res.string.delete_entry_title),
            text = stringResource(Res.string.delete_files_too, current?.attachments?.size ?: 0),
            confirmLabel = stringResource(Res.string.delete),
            onConfirm = { if (context.actions.run(RemoveHolding(event.id, initial.id))) onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "Received…" on an awaited fee (ITERATION_4 §2.2): who got it, and how much of it (all, by default). */
@Composable
private fun ReceiveDialog(context: EventContext, awaited: Holding, parties: List<String>, onDismiss: () -> Unit) {
    val event = context.event
    val lang = language
    val fund = stringResource(Res.string.band_fund)
    var partyId by remember { mutableStateOf(context.me?.id?.takeIf { it in parties } ?: parties.first()) }
    var amount by remember { mutableStateOf(formatMoneyInput(awaited.amount, lang)) }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive && it <= awaited.amount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.receive_awaited_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(partyId, parties, { partyId = it }, stringResource(Res.string.received_by)) { partyName(context.band, event, it, fund) }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Text(stringResource(Res.string.receive_awaited_hint, formatMoney(awaited.amount, context.currency, lang)), style = MaterialTheme.typography.bodySmall)
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                if (context.actions.run(ReceiveAwaited(event.id, awaited.id, newId(), partyId, parsed!!))) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
