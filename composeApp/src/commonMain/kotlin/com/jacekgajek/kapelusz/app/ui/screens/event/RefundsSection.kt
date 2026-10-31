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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.defer
import com.jacekgajek.kapelusz.app.generated.open_cost
import com.jacekgajek.kapelusz.app.generated.pending_from_earlier
import com.jacekgajek.kapelusz.app.generated.pending_hint
import com.jacekgajek.kapelusz.app.generated.refund_amount_here
import com.jacekgajek.kapelusz.app.generated.refund_here
import com.jacekgajek.kapelusz.app.generated.refund_how_much
import com.jacekgajek.kapelusz.app.generated.refund_max
import com.jacekgajek.kapelusz.app.generated.refund_of
import com.jacekgajek.kapelusz.app.generated.refunds
import com.jacekgajek.kapelusz.app.generated.refunds_empty
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.ui.components.CostRow
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.costTitle
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.app.ui.screens.AttachmentsSection
import com.jacekgajek.kapelusz.app.ui.screens.costStatusText
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.view.Refunds
import org.jetbrains.compose.resources.stringResource

/** Costs repaid out of this event: included ones, and outstanding costs of participants that could be. */
@Composable
fun RefundsSection(context: EventContext, onAddCost: () -> Unit, onOpenCost: (String) -> Unit) {
    val band = context.band
    val event = context.event
    var editing by remember { mutableStateOf<Cost?>(null) }
    val canEdit = context.can.editRefunds(event)
    val pending = Refunds.pending(context.view, event, canEdit)
    val me = context.me
    val mine = Refunds.mine(context.view, event, me, canEdit)

    SectionTitle(stringResource(Res.string.refunds))
    if (event.refunds.isEmpty() && pending.isEmpty() && mine.isEmpty()) {
        Text(stringResource(Res.string.refunds_empty), style = MaterialTheme.typography.bodyMedium)
    }
    event.refunds.forEach { refund ->
        val cost = band.cost(refund.costId) ?: return@forEach
        Row(
            Modifier.fillMaxWidth().clickable(enabled = canEdit) { editing = cost }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CostDescription(context, cost, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                MoneyText(refund.amount)
                if (refund.amount != cost.amount) {
                    Text(
                        stringResource(Res.string.refund_of, formatMoney(cost.amount, context.currency, language)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Changes how much of the cost is refunded here, not the cost itself: not the pencil.
            if (canEdit) {
                val label = stringResource(Res.string.refund_amount_here)
                IconButton(onClick = { editing = cost }) { Icon(Icons.Default.Tune, cost.description?.takeIf { it.isNotBlank() }?.let { "$label: $it" } ?: label) }
            }
        }
        CostFiles(context, cost)
    }
    if (pending.isNotEmpty()) {
        Text(stringResource(Res.string.pending_from_earlier), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        // Leaving one is a choice too: it waits for a later event.
        Text(stringResource(Res.string.pending_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pending.forEach { cost ->
            val outstanding = CostLedger.outstanding(band, cost)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CostDescription(context, cost, Modifier.weight(1f))
                    MoneyText(outstanding, modifier = Modifier.padding(start = 8.dp))
                    // The cost itself, in its own form; Back comes to the event.
                    EditButton(cost.description) { onOpenCost(cost.id) }
                }
                CostFiles(context, cost)
                // Adds the rest to what this event already refunds of it, if anything.
                OutlinedButton(
                    onClick = { context.actions.run(SetRefund(event.id, cost.id, CostLedger.maxRefundAt(band, cost, event))) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(Res.string.refund_here)) }
            }
        }
    }

    // A cost for this event, its refund included here (ITERATION_2 §7).
    if (mine.isNotEmpty()) MyWaitingCosts(context, mine, onOpenCost)
    if (context.can.addCostIn(event)) {
        TextButton(onClick = onAddCost) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(Res.string.add_cost), Modifier.padding(start = 8.dp))
        }
    }

    editing?.let { cost -> RefundDialog(context, cost, onOpenCost, onDismiss = { editing = null }) }
}

/** Who paid it, then what and when: the amount is beside it as a field or a figure of its own. */
@Composable
private fun CostDescription(context: EventContext, cost: Cost, modifier: Modifier) {
    val subtitle = "${costTitle(cost)} · ${formatDate(cost.date, language)}"
    CostRow(cost, subtitle, modifier, title = context.band.member(cost.memberId)?.name.orEmpty(), showAmount = false)
}

@Composable
private fun RefundDialog(context: EventContext, opened: Cost, onOpenCost: (String) -> Unit, onDismiss: () -> Unit) {
    val event = context.event
    // As it is now: its amount may have changed meanwhile, or it may be gone (V5-M1 C4d).
    val cost = context.band.cost(opened.id)
    if (cost == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val max = CostLedger.maxRefundAt(context.band, cost, event)
    val lang = language
    var amount by remember { mutableStateOf(formatMoneyInput(event.refundOf(cost.id)?.amount ?: max, lang)) }
    val parsed = parseMoney(amount)?.takeIf { !it.isNegative && it <= max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.refunds)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The cost itself, in its own form; Back comes to the event.
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(Res.string.open_cost)) {
                        onDismiss()
                        onOpenCost(cost.id)
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CostDescription(context, cost, Modifier.weight(1f))
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                // The cost's own amount is in its form: this is only the part refunded here.
                Text(stringResource(Res.string.refund_how_much), style = MaterialTheme.typography.bodyMedium)
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Text(stringResource(Res.string.refund_max, formatMoney(max, context.currency, lang)), style = MaterialTheme.typography.bodySmall)
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                if (context.actions.run(SetRefund(event.id, cost.id, parsed!!))) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { if (context.actions.run(SetRefund(event.id, cost.id, Money.ZERO))) onDismiss() }) {
                    Text(stringResource(Res.string.defer))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

/** The viewer's own costs that wait for a lead to refund them here; read only, opened on tap. */
@Composable
private fun MyWaitingCosts(context: EventContext, costs: List<Cost>, onOpenCost: (String) -> Unit) {
    val band = context.band
    val statuses = context.view.costStatuses
    Text(stringResource(Res.string.pending_from_earlier), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    costs.forEach { cost ->
        val status = statuses.status(cost)
        Row(
            Modifier.fillMaxWidth().clickable { onOpenCost(cost.id) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CostDescription(context, cost, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                MoneyText(CostLedger.outstanding(band, cost))
                Text(costStatusText(band, cost, status), style = MaterialTheme.typography.labelSmall)
            }
        }
        CostFiles(context, cost)
    }
}

/**
 * A cost's files (receipts) under it, to check before including it: thumbnails and PDFs, opened in the viewer; changed
 * on the cost's own screen (ITERATION_5 §4.8).
 */
@Composable
private fun CostFiles(context: EventContext, cost: Cost) {
    if (cost.attachments.isEmpty()) return
    // Under the text, past the category icon.
    Box(Modifier.padding(start = 52.dp)) {
        AttachmentsSection(
            context.fileStore,
            AttachmentOwner.OfCost(cost.id),
            cost.attachments,
            drafts = emptyList(),
            onDrafts = {},
            editable = false,
            actions = context.actions,
            title = null,
        )
    }
}
