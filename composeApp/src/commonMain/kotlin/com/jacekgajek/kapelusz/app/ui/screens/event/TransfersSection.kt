package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.confirm
import com.jacekgajek.kapelusz.app.generated.hand_over
import com.jacekgajek.kapelusz.app.generated.hand_over_from
import com.jacekgajek.kapelusz.app.generated.hand_over_hint
import com.jacekgajek.kapelusz.app.generated.hand_over_to
import com.jacekgajek.kapelusz.app.generated.keep_for_fund
import com.jacekgajek.kapelusz.app.generated.note_optional
import com.jacekgajek.kapelusz.app.generated.recorded_transfers
import com.jacekgajek.kapelusz.app.generated.remove
import com.jacekgajek.kapelusz.app.generated.remove_transfer_confirm
import com.jacekgajek.kapelusz.app.generated.remove_transfer_title
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_all
import com.jacekgajek.kapelusz.app.generated.settle_part
import com.jacekgajek.kapelusz.app.generated.to_settle
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.PartialSettleDialog
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.TransferParties
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SuggestedTransfer
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import org.jetbrains.compose.resources.stringResource

@Composable
fun TransfersSection(context: EventContext) {
    val event = context.event
    val settlement = context.settlement
    var handingOver by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Transfer?>(null) }

    if (settlement.canSettle && settlement.suggestions.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.to_settle))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                settlement.suggestions.forEach { suggestion -> SuggestionRow(context, suggestion) }
            }
        }
        if (context.can.settleAll(event)) Button(
            onClick = { context.actions.run(SettleSuggestions(event.id)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Icon(Icons.Default.DoneAll, null)
            Text(stringResource(Res.string.settle_all), Modifier.padding(start = 8.dp))
        }
    }
    val canHandOver = handOverSenders(context).isNotEmpty()
    if (canHandOver && event.holdings.isNotEmpty() && settlement.status != EventStatus.SETTLED) {
        OutlinedButton(onClick = { handingOver = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.SwapHoriz, null)
            Text(stringResource(Res.string.hand_over), Modifier.padding(start = 8.dp))
        }
    }

    if (event.transfers.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.recorded_transfers))
        val fundLabel = stringResource(Res.string.band_fund)
        event.transfers.forEach { transfer ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = context.can.removeTransfer(event)) { removing = transfer }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TransferParties(partyName(context.band, event, transfer.from, fundLabel), partyName(context.band, event, transfer.to, fundLabel))
                    transfer.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                MoneyText(transfer.amount, context.currency)
                if (context.can.removeTransfer(event)) RemoveButton { removing = transfer }
            }
        }
    }

    if (handingOver) HandOverDialog(context, onDismiss = { handingOver = false })
    removing?.let { transfer ->
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
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun SuggestionRow(context: EventContext, suggestion: SuggestedTransfer) {
    val band = context.band
    val fundLabel = stringResource(Res.string.band_fund)
    val keepsForFund = suggestion.to == BAND_FUND && suggestion.from == band.treasurerMemberId
    var partial by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // A size smaller than body text, so "Name → Name" fits beside the buttons on a phone (ITERATION_2 §7.4).
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) { SuggestionText(context, suggestion, keepsForFund, fundLabel) }
        }
        if (context.can.recordTransfer(context.event, suggestion.from, suggestion.to)) {
            if (!keepsForFund) TextButton(onClick = { partial = true }) { Text(stringResource(Res.string.settle_part)) }
            FilledTonalButton(onClick = {
                context.actions.run(RecordTransfer(context.event.id, newId(), suggestion.from, suggestion.to, suggestion.amount))
            }) { Text(stringResource(if (keepsForFund) Res.string.confirm else Res.string.settle)) }
        }
    }
    if (partial) {
        PartialSettleDialog(
            from = partyName(band, context.event, suggestion.from, fundLabel),
            to = partyName(band, context.event, suggestion.to, fundLabel),
            suggested = suggestion.amount,
            currency = context.currency,
            onConfirm = { context.actions.run(RecordTransfer(context.event.id, newId(), suggestion.from, suggestion.to, it)) },
            onDismiss = { partial = false },
        )
    }
}

/** Everyone who can take part in a transfer of this event: participants, plus the band fund if it is set up. */
private fun transferParties(context: EventContext): List<String> {
    val lines = context.settlement.lines.map { it.partyId }.ifEmpty { context.event.participants.map { it.partyId } }
    return lines + (if (context.band.treasurerMemberId != null && BAND_FUND !in lines) listOf(BAND_FUND) else emptyList())
}

/** Recipients the user may record a transfer to from [from]. */
private fun handOverRecipients(context: EventContext, from: String): List<String> =
    transferParties(context).filter { it != from && context.can.recordTransfer(context.event, from, it) }

/** Parties the user may record a hand-over from (themselves for members, anyone for leads). */
private fun handOverSenders(context: EventContext): List<String> =
    transferParties(context).filter { handOverRecipients(context, it).isNotEmpty() }

/** Manual transfer, e.g. one holder handing all collected money to another who then pays everyone. */
@Composable
private fun HandOverDialog(context: EventContext, onDismiss: () -> Unit) {
    val event = context.event
    val settlement = context.settlement
    val fundLabel = stringResource(Res.string.band_fund)
    val senders = handOverSenders(context)
    val holders = settlement.lines.filter { it.balance.isPositive && it.partyId in senders }.map { it.partyId }
    val lang = language
    val firstFrom = context.me?.id?.takeIf { it in holders } ?: holders.firstOrNull() ?: senders.firstOrNull()
    val firstTo = firstFrom?.let { handOverRecipients(context, it).firstOrNull() }
    // A sync may have left nobody to hand over from or to (e.g. the user was removed from the event).
    if (firstFrom == null || firstTo == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var from by remember { mutableStateOf(firstFrom) }
    var to by remember { mutableStateOf(firstTo) }
    var amount by remember { mutableStateOf(settlement.line(from)?.balance?.takeIf { it.isPositive }?.let { formatMoneyInput(it, lang) } ?: "") }
    var note by remember { mutableStateOf("") }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.hand_over)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.hand_over_hint), style = MaterialTheme.typography.bodySmall)
                Dropdown(from, senders, {
                    from = it
                    if (to !in handOverRecipients(context, it)) to = handOverRecipients(context, it).firstOrNull() ?: to
                    settlement.line(it)?.balance?.takeIf { balance -> balance.isPositive }?.let { balance -> amount = formatMoneyInput(balance, lang) }
                }, stringResource(Res.string.hand_over_from)) { partyName(context.band, event, it, fundLabel) }
                Dropdown(to, handOverRecipients(context, from), { to = it }, stringResource(Res.string.hand_over_to)) {
                    partyName(context.band, event, it, fundLabel)
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.note_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && from != to, onClick = {
                if (context.actions.run(RecordTransfer(event.id, newId(), from, to, parsed!!, note.ifBlank { null }))) onDismiss()
            }) { Text(stringResource(Res.string.hand_over)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun SuggestionText(context: EventContext, suggestion: SuggestedTransfer, keepsForFund: Boolean, fundLabel: String) {
    val band = context.band
    if (keepsForFund) {
        Text(
            stringResource(
                Res.string.keep_for_fund,
                partyName(band, context.event, suggestion.from, fundLabel),
                formatMoney(suggestion.amount, context.currency, language),
            ),
        )
    } else {
        TransferParties(partyName(band, context.event, suggestion.from, fundLabel), partyName(band, context.event, suggestion.to, fundLabel))
        MoneyText(suggestion.amount, context.currency, bold = true)
    }
}
