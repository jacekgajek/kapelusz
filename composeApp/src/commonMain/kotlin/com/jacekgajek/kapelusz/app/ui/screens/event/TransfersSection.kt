package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.amount_copied
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.confirm
import com.jacekgajek.kapelusz.app.generated.copy_failed
import com.jacekgajek.kapelusz.app.generated.hand_over
import com.jacekgajek.kapelusz.app.generated.hand_over_from
import com.jacekgajek.kapelusz.app.generated.hand_over_hint
import com.jacekgajek.kapelusz.app.generated.hand_over_max
import com.jacekgajek.kapelusz.app.generated.hand_over_to
import com.jacekgajek.kapelusz.app.generated.keep_for_fund
import com.jacekgajek.kapelusz.app.generated.note_optional
import com.jacekgajek.kapelusz.app.generated.pair_names
import com.jacekgajek.kapelusz.app.generated.recorded_transfers
import com.jacekgajek.kapelusz.app.generated.remove
import com.jacekgajek.kapelusz.app.generated.remove_transfer_confirm
import com.jacekgajek.kapelusz.app.generated.remove_transfer_title
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_all
import com.jacekgajek.kapelusz.app.generated.settle_part
import com.jacekgajek.kapelusz.app.generated.settled_all
import com.jacekgajek.kapelusz.app.generated.to_settle
import com.jacekgajek.kapelusz.app.generated.transfer_recorded
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.app.ui.components.CollapsibleHeader
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.PartialSettleDialog
import com.jacekgajek.kapelusz.app.ui.components.PaymentButtons
import com.jacekgajek.kapelusz.app.ui.components.PaymentKind
import com.jacekgajek.kapelusz.app.ui.components.PaymentRow
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.TransferParties
import com.jacekgajek.kapelusz.app.ui.components.UiMessage
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.components.payeeName
import com.jacekgajek.kapelusz.app.ui.components.resolved
import com.jacekgajek.kapelusz.app.ui.components.settleLabel
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.Payees
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import com.jacekgajek.kapelusz.text.BlikPhone
import com.jacekgajek.kapelusz.view.HandOver
import com.jacekgajek.kapelusz.view.Settling
import com.jacekgajek.kapelusz.view.partyName
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun TransfersSection(context: EventContext, shareSettlement: (() -> Unit)?) {
    val event = context.event
    val settlement = context.settlement
    var dialog by remember { mutableStateOf<TransfersDialog?>(null) }

    if (settlement.canSettle && settlement.suggestions.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.to_settle))
        settlement.suggestions.forEach { suggestion ->
            // Keyed by the parties, card and all: an open "Part" dialog stays with its people when a sync reorders the
            // list or a Settle takes a row away.
            key(suggestion.from, suggestion.to) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { SuggestionRow(context, suggestion) }
                }
            }
        }
        // One tap with Undo in the snackbar (ITERATION_5 §4.1), as Settle and Part are.
        val settledAll = stringResource(Res.string.settled_all)
        if (context.can.settleAll(event)) {
            Button(
                onClick = { context.actions.runWithUndo(SettleSuggestions(event.id), settledAll) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Default.DoneAll, null)
                Text(stringResource(Res.string.settle_all), Modifier.padding(start = 8.dp))
            }
        }
    }
    val canHandOver = handOverOf(context).senders.isNotEmpty()
    if (canHandOver && event.holdings.isNotEmpty() && settlement.status != EventStatus.SETTLED) {
        OutlinedButton(onClick = { dialog = TransfersDialog.HandingOver }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.SwapHoriz, null)
            Text(stringResource(Res.string.hand_over), Modifier.padding(start = 8.dp))
        }
    }
    // The settlement's three actions together: settle all, hand over, share.
    shareSettlement?.let { ShareSettlementButton(it) }

    // Done already: folded away, opened on demand (each visit starts folded).
    var showRecorded by remember(event.id) { mutableStateOf(false) }
    if (event.transfers.isNotEmpty()) {
        CollapsibleHeader("${stringResource(Res.string.recorded_transfers)} (${event.transfers.size})", showRecorded) { showRecorded = !showRecorded }
    }
    if (event.transfers.isNotEmpty() && showRecorded) {
        val fundLabel = stringResource(Res.string.band_fund)
        event.transfers.forEach { transfer ->
            val remove = { dialog = TransfersDialog.Removing(transfer) }
            val removable = context.can.removeTransfer(event, transfer)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = removable, onClick = remove).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val from = partyName(context.band, event, transfer.from, fundLabel)
                val to = partyName(context.band, event, transfer.to, fundLabel)
                Column(Modifier.weight(1f)) {
                    TransferParties(from, to)
                    transfer.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                MoneyText(transfer.amount)
                if (removable) RemoveButton("$from → $to, ${formatMoney(transfer.amount, context.currency, language)}", onClick = remove)
            }
        }
    }

    when (val open = dialog) {
        TransfersDialog.HandingOver -> HandOverDialog(context, onDismiss = { dialog = null })

        is TransfersDialog.Removing -> {
            val transfer = open.transfer
            val fundLabel = stringResource(Res.string.band_fund)
            ConfirmDialog(
                title = stringResource(Res.string.remove_transfer_title),
                text = stringResource(
                    Res.string.remove_transfer_confirm,
                    partyName(context.band, event, transfer.from, fundLabel),
                    partyName(context.band, event, transfer.to, fundLabel),
                    formatMoney(transfer.amount, context.currency, language),
                ),
                confirmLabel = stringResource(Res.string.remove),
                onConfirm = { context.actions.run(RemoveTransfer(event.id, transfer.id)) },
                onDismiss = { dialog = null },
            )
        }

        null -> Unit
    }
}

/** What is open over the transfers: one at a time. */
private sealed interface TransfersDialog {
    data object HandingOver : TransfersDialog
    data class Removing(val transfer: Transfer) : TransfersDialog
}

@Composable
private fun SuggestionRow(context: EventContext, suggestion: SuggestedTransfer) {
    val band = context.band
    val fundLabel = stringResource(Res.string.band_fund)
    val settlement = context.settlement
    // The treasurer keeps it, also for a pair they settle in (ITERATION_5 §7).
    val keepsForFund = Settling.keepsForFund(band, settlement, suggestion)
    var partial by remember { mutableStateOf(false) }
    val lang = language
    val pair = stringResource(Res.string.pair_names)
    val from = partyName(band, context.event, settlement, suggestion.from, fundLabel, pair)
    val to = partyName(band, context.event, settlement, suggestion.to, fundLabel, pair)

    // "Recorded: Ania → Bartek 250,00 zł", said with Undo.
    fun recorded(amount: Money) = UiMessage.Text(Res.string.transfer_recorded, listOf(from, to, formatMoney(amount, context.currency, lang)))
    // The names get the full width; the amount and the buttons go below them, so long names never squeeze the buttons.
    // A size smaller than body text, so "Name → Name" mostly fits on one line on a phone (ITERATION_2 §7.4).
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val settles = context.can.settleSuggestion(context.event, suggestion.from, suggestion.to)
        val label = settleLabel(band, settlement, context.me?.id, suggestion.from, suggestion.to)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) { SuggestionText(context, suggestion, keepsForFund, from, to) }
            }
            // Only to who pays it (ITERATION_5 §8): the recipient would read the icon as owing; the fund's holder keeping it pays nobody.
            if (settles && !keepsForFund && BlikPhone.pays(band, settlement, context.me?.id, suggestion.from)) PaymentButtons(Payees.forTransfer(band, settlement, suggestion.from, suggestion.to, suggestion.amount, context.me?.id))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (!keepsForFund) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CopyAmountButton(context, suggestion.amount)
                        MoneyText(suggestion.amount, bold = true)
                    }
                }
            }
            if (settles) {
                if (!keepsForFund) TextButton(onClick = { partial = true }) { Text(stringResource(Res.string.settle_part)) }
                FilledTonalButton(onClick = {
                    context.actions.runWithUndo(settleOp(context, suggestion, suggestion.amount), recorded(suggestion.amount))
                }) { Text(stringResource(if (keepsForFund) Res.string.confirm else label)) }
            }
        }
    }
    if (partial) {
        PartialSettleDialog(
            from = from,
            to = to,
            suggested = suggestion.amount,
            currency = context.currency,
            onConfirm = { context.actions.runWithUndo(settleOp(context, suggestion, it), recorded(it)) },
            onDismiss = { partial = false },
            confirmLabel = settleLabel(band, settlement, context.me?.id, suggestion.from, suggestion.to),
            actions = context.actions,
        )
    }
}

/**
 * An icon that copies the amount alone, as typed into a bank app (`2304,45` in Polish, no currency), e.g. for a transfer
 * (ITERATION_5 §3).
 */
@Composable
internal fun CopyAmountButton(context: EventContext, amount: Money) {
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    val text = formatMoneyInput(amount, language)
    val copied = stringResource(Res.string.amount_copied, text)
    val failed = stringResource(Res.string.copy_failed)
    // Just the icon beside the amount; screen readers skip it (the amount itself is read out).
    Icon(
        Icons.Default.ContentCopy,
        null,
        Modifier
            .padding(end = 6.dp)
            .size(18.dp)
            .clickable { scope.launch { context.actions.say(if (copy(text)) copied else failed) } }
            .clearAndSetSemantics {},
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun handOverOf(context: EventContext) = HandOver(context.view, context.event, context.settlement, context.can)

/** Manual transfer, e.g. one holder handing all collected money to another who then pays everyone. */
@Composable
private fun HandOverDialog(context: EventContext, onDismiss: () -> Unit) {
    val event = context.event
    val settlement = context.settlement
    val fundLabel = stringResource(Res.string.band_fund)
    val handOver = handOverOf(context)
    val senders = handOver.senders
    val lang = language
    val defaultFrom = context.me?.id?.takeIf { it in senders } ?: senders.firstOrNull()
    // A sync may have left nobody to hand over from or to (e.g. the user was removed from the event).
    val defaults = defaultFrom?.let { from -> handOver.recipients(from).firstOrNull()?.let { from to it } }
    val (firstFrom, firstTo) = resolved(defaults, onDismiss) ?: return
    var from by remember { mutableStateOf(firstFrom) }
    var to by remember { mutableStateOf(firstTo) }
    var amount by remember { mutableStateOf(handOver.amount(from).takeIf { it.isPositive }?.let { formatMoneyInput(it, lang) } ?: "") }
    var note by remember { mutableStateOf("") }
    // At most what the sender holds (an artist: owes back): a hand-over passes on money, it doesn't make any up.
    val held = handOver.amount(from)
    val parsed = parseMoney(amount)?.takeIf { it.isPositive && it <= held }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.hand_over)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.hand_over_hint), style = MaterialTheme.typography.bodySmall)
                Dropdown(from, senders, {
                    from = it
                    if (to !in handOver.recipients(it)) to = handOver.recipients(it).firstOrNull() ?: to
                    handOver.amount(it).takeIf { held -> held.isPositive }?.let { held -> amount = formatMoneyInput(held, lang) }
                }, stringResource(Res.string.hand_over_from)) { partyName(context.band, event, it, fundLabel) }
                Dropdown(to, handOver.recipients(from), { to = it }, stringResource(Res.string.hand_over_to)) {
                    partyName(context.band, event, it, fundLabel)
                }
                // The recipient's BLIK number and account, if given, when the user hands over their own money (ITERATION_5 §8,
                // ITERATION_7 §7).
                Payees.forHandOver(context.band, to).firstOrNull()?.takeIf { BlikPhone.pays(context.band, null, context.me?.id, from) }?.let { payee ->
                    PaymentKind.entries.forEach { kind -> PaymentRow(kind, stringResource(kind.title), kind.of(payee), payeeName(payee)) }
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Text(stringResource(Res.string.hand_over_max, formatMoney(held, context.currency, lang)), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.note_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && from != to, onClick = {
                // Recorded with Undo in the snackbar (ITERATION_5 §4.6), as Settle is.
                val recorded = UiMessage.Text(
                    Res.string.transfer_recorded,
                    listOf(
                        partyName(context.band, event, from, fundLabel),
                        partyName(context.band, event, to, fundLabel),
                        formatMoney(parsed!!, context.currency, lang),
                    ),
                )
                onDismiss()
                context.actions.runWithUndo(RecordTransfer(event.id, newId(), from, to, parsed, note.ifBlank { null }), recorded)
            }) { Text(stringResource(Res.string.hand_over)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun SuggestionText(context: EventContext, suggestion: SuggestedTransfer, keepsForFund: Boolean, from: String, to: String) {
    val band = context.band
    if (keepsForFund) {
        Text(
            stringResource(
                Res.string.keep_for_fund,
                partyName(band, context.event, band.treasurerMemberId!!, ""),
                formatMoney(suggestion.amount, context.currency, language),
            ),
        )
    } else {
        TransferParties(from, to)
    }
}

/** Records [amount] of a suggestion; a pair's is recorded between the real people (ITERATION_5 §7). */
private fun settleOp(context: EventContext, suggestion: SuggestedTransfer, amount: Money) = RecordTransfer(context.event.id, newId(), suggestion.from, suggestion.to, amount, together = true)
