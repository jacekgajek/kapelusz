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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import com.jacekgajek.kapelusz.app.generated.gross
import com.jacekgajek.kapelusz.app.generated.net
import com.jacekgajek.kapelusz.app.generated.note_optional
import com.jacekgajek.kapelusz.app.generated.organiser
import com.jacekgajek.kapelusz.app.generated.receive_awaited
import com.jacekgajek.kapelusz.app.generated.receive_awaited_hint
import com.jacekgajek.kapelusz.app.generated.receive_awaited_title
import com.jacekgajek.kapelusz.app.generated.receive_awaited_whole
import com.jacekgajek.kapelusz.app.generated.received_by
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.source
import com.jacekgajek.kapelusz.app.generated.vat_mode_net
import com.jacekgajek.kapelusz.app.generated.vat_rate
import com.jacekgajek.kapelusz.app.generated.vat_split_gross
import com.jacekgajek.kapelusz.app.generated.vat_split_net
import com.jacekgajek.kapelusz.app.generated.vat_summary_gross
import com.jacekgajek.kapelusz.app.generated.vat_summary_net
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.ui.components.Choice
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.IntegerField
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.SegmentedChoice
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.resolved
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.format.formatMoneyInput
import com.jacekgajek.kapelusz.app.ui.screens.AttachmentsSection
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Vat
import com.jacekgajek.kapelusz.model.VatSplitMode
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

/** "Where the money sits": who collected how much, from which source. */
@Composable
fun CollectedSection(context: EventContext, dialogs: CollectedDialogs) {
    val event = context.event
    val lang = language
    val amountText: (Money) -> String = { formatMoneyInput(it, lang) }
    val allowedParties = context.can.holdingParties(event)
    val receiveParties = context.can.receiveParties(event)
    // A fee paid straight to the band's account is held by the fund.

    SectionTitle(stringResource(Res.string.collected_money))
    if (event.holdings.isEmpty()) Text(stringResource(Res.string.collected_empty), style = MaterialTheme.typography.bodyMedium)
    event.holdings.forEach { holding ->
        val edit = { dialogs.editEntry(holding, allowedParties, amountText, context.defaultVat) }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = context.can.editHolding(event, holding), onClick = edit).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (holding.awaited) {
                        stringResource(Res.string.organiser)
                    } else {
                        partyName(context.band, event, holding.partyId, stringResource(Res.string.band_fund))
                    },
                )
                // Source, note, "awaited", and the VAT breakdown.
                val vat = holding.vat?.let {
                    if (it.split == VatSplitMode.GROSS) {
                        stringResource(Res.string.vat_summary_net, formatMoney(it.net, context.currency, lang), it.rate)
                    } else {
                        stringResource(Res.string.vat_summary_gross, formatMoney(holding.amount, context.currency, lang), it.rate)
                    }
                }
                val awaited = stringResource(Res.string.awaited_payment).takeIf { holding.awaited }
                Text(
                    listOfNotNull(holding.source.label(), holding.note, awaited, vat).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (holding.awaited) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
            }
            MoneyText(holding.settled)
            if (context.can.editHolding(event, holding)) EditButton(onClick = edit)
        }

        // A fee the organiser still owes: paid (whoever got it takes it over), or never paid (it goes).
        // Laid out like Refund here under a waiting cost.
        if (holding.awaited && receiveParties.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (context.can.editHolding(event, holding)) {
                    TextButton(onClick = { dialogs.welch(holding) }) {
                        Text(stringResource(Res.string.awaited_welched), color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(onClick = { dialogs.receive(holding, receiveParties, context.me?.id) }) {
                    Text(stringResource(Res.string.receive_awaited))
                }
            }
        }
        // Its files (e.g. the contract), for everyone who sees the money to open; changed in its dialog.
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
    // A sync may have taken away whom the user can record for (removed from the event, a role changed): nothing to add.
    val defaultParty = context.me?.id?.takeIf { it in allowedParties } ?: allowedParties.firstOrNull()
    if (defaultParty != null) {
        TextButton(onClick = { dialogs.addEntry(defaultParty, allowedParties, amountText, defaultVat = context.defaultVat) }) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(Res.string.add_money), Modifier.padding(start = 8.dp))
        }
    }
    if (context.can.recordAwaited(event)) {
        TextButton(onClick = { dialogs.addAwaited(amountText, context.defaultVat) }) {
            Icon(Icons.Default.Schedule, null)
            Text(stringResource(Res.string.add_awaited), Modifier.padding(start = 8.dp))
        }
    }
    // Photos or PDFs shared to Kapelusz for new received money here: the new entry's dialog, with them.
    // Shared while it was open already: it starts again with them, rather than taking only what still fits.
    LaunchedEffect(Unit) {
        val files = context.takeCollectedFiles() ?: return@LaunchedEffect
        defaultParty?.let { dialogs.addEntry(it, allowedParties, amountText, files, context.defaultVat) }
    }
    val close = dialogs::dismiss
    when (val open = dialogs.open) {
        is CollectedDialog.Entry -> HoldingDialog(context, open.form, onDismiss = close)

        is CollectedDialog.Welching -> {
            // As it is now: part of it may have come in meanwhile, on another phone.
            val holding = resolved(event.holdings.firstOrNull { it.id == open.holding.id }, close)
            if (holding != null) WelchedDialog(context, holding, onDismiss = close)
        }

        is CollectedDialog.Receive -> {
            // Received meanwhile (e.g. on another phone): nothing left to receive.
            val awaited = event.holdings.firstOrNull { it.id == open.form.awaited.id }?.takeIf { receiveParties.isNotEmpty() }
            val current = resolved(awaited, close)
            if (current != null) ReceiveDialog(context, open.form, current, onDismiss = close)
        }

        null -> Unit
    }
}

/** An awaited fee that will never come: asked first, as its files (the contract) go with it. */
@Composable
private fun WelchedDialog(context: EventContext, holding: Holding, onDismiss: () -> Unit) {
    val files = holding.attachments.size
    ConfirmDialog(
        title = stringResource(Res.string.awaited_welched_title),
        text = stringResource(Res.string.awaited_welched_text, formatMoney(holding.settled, context.currency, language)) +
            // Its files (the contract) go with it, for good.
            files.takeIf { it > 0 }?.let { " " + stringResource(Res.string.delete_files_too, it) }.orEmpty(),
        confirmLabel = stringResource(Res.string.awaited_welched),
        onConfirm = { context.actions.run(RemoveHolding(context.event.id, holding.id)) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun HoldingDialog(context: EventContext, form: HoldingForm, onDismiss: () -> Unit) {
    val event = context.event
    val initial = form.initial
    // Gone meanwhile (removed, or an awaited fee received on another phone): nothing left to edit.
    if (initial != null) {
        resolved(event.holdings.firstOrNull { it.id == initial.id }, onDismiss)
            ?: return
    }
    // The entry as it is now (a file added in the dialog shows at once).
    val current = initial?.let { h -> event.holdings.firstOrNull { it.id == h.id } } ?: initial
    val run = context.actions::run

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.collected_money)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form.choosesParty) {
                    Dropdown(form.partyId, form.parties, { form.partyId = it }, stringResource(Res.string.collected_by)) {
                        partyName(context.band, event, it, "")
                    }
                }
                val amount = form.amount
                val vatModeChoices = listOf(
                    Choice(false, stringResource(Res.string.amount)),
                    Choice(true, stringResource(Res.string.vat_mode_net)),
                )
                SegmentedChoice(vatModeChoices, amount.withVat, { amount.withVat = it })
                if (amount.withVat) {
                    val splitChoices = listOf(
                        Choice(VatSplitMode.GROSS, stringResource(Res.string.vat_split_gross)),
                        Choice(VatSplitMode.NET, stringResource(Res.string.vat_split_net)),
                    )
                    SegmentedChoice(splitChoices, amount.split, { amount.split = it })
                    MoneyField(amount.grossText, { amount.typeGross(it) }, stringResource(Res.string.gross))
                    IntegerField(amount.vatText, { amount.typeVat(it) }, stringResource(Res.string.vat_rate), validRange = 1..100)
                    MoneyField(amount.netText, { amount.typeNet(it) }, stringResource(Res.string.net))
                } else {
                    MoneyField(amount.grossText, { amount.typeGross(it) }, stringResource(Res.string.amount))
                }
                Dropdown(form.source, IncomeSource.entries, { form.source = it }, stringResource(Res.string.source)) { it.label() }

                OutlinedTextField(
                    form.note,
                    { form.note = it },
                    label = { Text(stringResource(Res.string.note_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AttachmentsSection(
                    context.fileStore,
                    owner = current?.let { AttachmentOwner.OfHolding(event.id, it.id) },
                    attachments = current?.attachments.orEmpty(),
                    drafts = form.drafts,
                    onDrafts = { form.drafts = it },
                    editable = true,
                    actions = context.actions,
                    hint = Res.string.attachment_hint_holding,
                )
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(enabled = form.ready, onClick = {
                val attach = { id: String, drafts: List<PreparedFile> ->
                    context.fileStore.attachAfterSave(AttachmentOwner.OfHolding(event.id, id), drafts)
                }
                if (form.save(run, attach)) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    // Its files go with it, for good (no undo), so that is asked first.
                    TextButton(onClick = {
                        if (current?.attachments.isNullOrEmpty()) {
                            if (form.delete(run)) onDismiss()
                        } else {
                            form.confirmingDelete = true
                        }
                    }) {
                        Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
    if (form.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(Res.string.delete_entry_title),
            text = stringResource(Res.string.delete_files_too, current?.attachments?.size ?: 0),
            confirmLabel = stringResource(Res.string.delete),
            onConfirm = { if (form.delete(run)) onDismiss() },
            onDismiss = { form.confirmingDelete = false },
        )
    }
}

/**
 * "Received…" on an awaited fee: who got it.
 */
@Composable
private fun ReceiveDialog(context: EventContext, form: ReceiveForm, awaited: Holding, onDismiss: () -> Unit) {
    val event = context.event
    val fund = stringResource(Res.string.band_fund)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.receive_awaited_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown(form.partyId, form.parties, { form.partyId = it }, stringResource(Res.string.received_by)) {
                    partyName(context.band, event, it, fund)
                }
                // Whole: the fee as it is now (changed meanwhile: the band refuses it, and it is looked at again).
                Text(stringResource(Res.string.receive_awaited_whole, formatMoney(awaited.amount, context.currency, language)))
                awaited.vat?.let { VatSummary(it, context.currency) }
                Text(stringResource(Res.string.receive_awaited_hint), style = MaterialTheme.typography.bodySmall)
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (form.save(context.actions::run)) onDismiss() }) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The net and the rate of a gross with VAT in it, in small print. */
@Composable
fun VatSummary(vat: Vat, currency: String) {
    val net = formatMoney(vat.net, currency, language)
    Text(stringResource(Res.string.vat_summary_net, net, vat.rate), style = MaterialTheme.typography.bodySmall)
}
