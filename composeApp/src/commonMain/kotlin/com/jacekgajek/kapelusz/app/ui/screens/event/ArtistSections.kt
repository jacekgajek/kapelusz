package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.artist_my_costs
import com.jacekgajek.kapelusz.app.generated.artist_payout
import com.jacekgajek.kapelusz.app.generated.artist_received
import com.jacekgajek.kapelusz.app.generated.artist_still_to_receive
import com.jacekgajek.kapelusz.app.generated.artist_to_receive
import com.jacekgajek.kapelusz.app.generated.participants
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.app.ui.screens.RefusedCostDialog
import com.jacekgajek.kapelusz.app.ui.screens.labelRes
import com.jacekgajek.kapelusz.domain.CostStatus
import com.jacekgajek.kapelusz.domain.CostStatuses
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Money
import org.jetbrains.compose.resources.stringResource

// What an artist sees of an event they play (ITERATION_4 §1.1): who plays, their own payout and their own costs; none
// of the band's money.

/** Who plays: names only, no weights or shares. */
@Composable
fun ArtistLineup(context: EventContext) {
    val event = context.event
    SectionTitle(stringResource(Res.string.participants))
    event.participants.forEach { participant ->
        val name = partyName(context.band, event, participant.partyId, "")
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name, context.band.member(participant.partyId)?.pictureUrl, decorative = true)
            Text(name)
        }
    }
}

/** What the artist gets from this event (their share and refunds), how much came, how much is still to come; never from whom. */
@Composable
fun ArtistPayout(context: EventContext) {
    val line = context.me?.let { context.settlement.line(it.id) } ?: return
    SectionTitle(stringResource(Res.string.artist_payout))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PayoutRow(stringResource(Res.string.artist_to_receive), line.target, context.currency)
            PayoutRow(stringResource(Res.string.artist_received), line.received - line.sent, context.currency)
            // Artists hold no money (ITERATION_4 §1.1), so what is still owed to them is just the negative delta.
            PayoutRow(stringResource(Res.string.artist_still_to_receive), -line.delta, context.currency, bold = true)
        }
    }
}

@Composable
private fun PayoutRow(label: String, amount: Money, currency: String, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        MoneyText(amount, currency, bold = bold)
    }
}

/** The artist's own costs for this event, with their statuses, and adding one. */
@Composable
fun ArtistCosts(context: EventContext, onOpenCost: (String) -> Unit, onAddCost: () -> Unit) {
    val band = context.band
    val me = context.me ?: return
    val costs = band.costs.filter { it.memberId == me.id && it.eventId == context.event.id }.sortedByDescending { it.date }
    val statuses = remember(band) { CostStatuses(band) }
    var asking by remember { mutableStateOf<Cost?>(null) }
    SectionTitle(stringResource(Res.string.artist_my_costs))
    costs.forEach { cost ->
        val status = statuses.status(cost)
        Row(
            Modifier.fillMaxWidth().clickable { if (status == CostStatus.REFUSED) asking = cost else onOpenCost(cost.id) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost))
                Text(formatDate(cost.date, language), style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(cost.amount, context.currency)
                Text(
                    stringResource(status.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == CostStatus.REFUSED) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        }
    }
    if (context.can.addCostIn(context.event)) TextButton(onClick = onAddCost) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(Res.string.add_cost), Modifier.padding(start = 8.dp))
    }
    asking?.let { cost -> RefusedCostDialog(cost, context.actions, onEdit = { onOpenCost(cost.id) }, onDismiss = { asking = null }) }
}
