package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.generated.add_cost
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.defer
import com.jacekgajek.kapelusz.app.generated.include
import com.jacekgajek.kapelusz.app.generated.pending_from_earlier
import com.jacekgajek.kapelusz.app.generated.refund_max
import com.jacekgajek.kapelusz.app.generated.refund_of
import com.jacekgajek.kapelusz.app.generated.refunds
import com.jacekgajek.kapelusz.app.generated.refunds_empty
import com.jacekgajek.kapelusz.app.generated.refuse
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.section_waiting_count
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.CollapsibleHeader
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionState
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sumOfMoney
import com.jacekgajek.kapelusz.ops.RefuseCost
import com.jacekgajek.kapelusz.ops.SetRefund
import org.jetbrains.compose.resources.stringResource

/** Costs repaid out of this event: included ones, and outstanding costs of participants that could be. */
@Composable
fun RefundsSection(context: EventContext, section: SectionState, onAddCost: () -> Unit) {
    val band = context.band
    val event = context.event
    var editing by remember { mutableStateOf<Cost?>(null) }
    val canEdit = context.can.editRefunds(event)
    val pending = if (canEdit) CostLedger.pendingFromEarlier(band, event) else emptyList()

    // Closed: the total refunded here, and how many costs wait for a lead.
    val refunded = formatMoney(event.refunds.sumOfMoney { it.amount }, context.currency, language)
    val waiting = stringResource(Res.string.section_waiting_count, pending.size)
    CollapsibleHeader(stringResource(Res.string.refunds), section, summary = {
        Text(listOfNotNull(refunded, waiting.takeIf { pending.isNotEmpty() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    })
    if (!section.expanded) return
    if (event.refunds.isEmpty() && pending.isEmpty()) {
        Text(stringResource(Res.string.refunds_empty), style = MaterialTheme.typography.bodyMedium)
    }
    event.refunds.forEach { refund ->
        val cost = band.cost(refund.costId) ?: return@forEach
        Row(
            Modifier.fillMaxWidth().clickable(enabled = canEdit) { editing = cost }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CostDescription(context, cost, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(refund.amount, context.currency)
                if (refund.amount != cost.amount) {
                    Text(
                        stringResource(Res.string.refund_of, formatMoney(cost.amount, context.currency, language)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (canEdit) EditButton { editing = cost }
        }
    }
    if (pending.isNotEmpty()) {
        Text(stringResource(Res.string.pending_from_earlier), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        pending.forEach { cost ->
            val outstanding = CostLedger.outstanding(band, cost)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CostDescription(context, cost, Modifier.weight(1f))
                    MoneyText(outstanding, context.currency, Modifier.padding(start = 8.dp))
                }
                // Include it here, or refuse it for good (ITERATION_4 §1.2): only a cost with nothing refunded yet.
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (context.can.refuseCost() && outstanding == cost.amount) {
                        TextButton(onClick = { context.actions.run(RefuseCost(cost.id)) }) {
                            Text(stringResource(Res.string.refuse), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // Adds the rest to what this event already refunds of it, if anything.
                    OutlinedButton(onClick = { context.actions.run(SetRefund(event.id, cost.id, CostLedger.maxRefundAt(band, cost, event))) }) {
                        Text(stringResource(Res.string.include))
                    }
                }
            }
        }
    }

    // A cost for this event, its refund included here (ITERATION_2 §7).
    if (context.can.addCostIn(event)) TextButton(onClick = onAddCost) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(Res.string.add_cost), Modifier.padding(start = 8.dp))
    }

    editing?.let { cost -> RefundDialog(context, cost, onDismiss = { editing = null }) }
}

@Composable
private fun CostDescription(context: EventContext, cost: Cost, modifier: Modifier) {
    Column(modifier) {
        Text(context.band.member(cost.memberId)?.name.orEmpty())
        Text(
            listOfNotNull(
                cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost),
                formatDate(cost.date, language),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RefundDialog(context: EventContext, cost: Cost, onDismiss: () -> Unit) {
    val event = context.event
    val max = CostLedger.maxRefundAt(context.band, cost, event)
    val lang = language
    var amount by remember { mutableStateOf(formatMoneyInput(event.refundOf(cost.id)?.amount ?: max, lang)) }
    val parsed = parseMoney(amount)?.takeIf { !it.isNegative && it <= max }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.refunds)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CostDescription(context, cost, Modifier)
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Text(stringResource(Res.string.refund_max, formatMoney(max, context.currency, lang)), style = MaterialTheme.typography.bodySmall)
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
