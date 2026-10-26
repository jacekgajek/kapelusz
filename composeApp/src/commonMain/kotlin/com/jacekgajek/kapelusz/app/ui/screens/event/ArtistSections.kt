package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.ui.components.CostIcon
import com.jacekgajek.kapelusz.app.ui.components.NotInAppIcon
import com.jacekgajek.kapelusz.app.ui.components.GuestIcon
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.artist_kept_by_partner
import com.jacekgajek.kapelusz.app.generated.artist_my_costs
import com.jacekgajek.kapelusz.app.generated.artist_payout
import com.jacekgajek.kapelusz.app.generated.artist_received
import com.jacekgajek.kapelusz.app.generated.artist_settles_with
import com.jacekgajek.kapelusz.app.generated.artist_still_to_receive
import com.jacekgajek.kapelusz.app.generated.artist_to_hand_back
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
import com.jacekgajek.kapelusz.app.ui.screens.costStatusText
import com.jacekgajek.kapelusz.domain.CostStatuses
import com.jacekgajek.kapelusz.domain.SettleTogether
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
            val member = context.band.member(participant.partyId)
            if (event.guest(participant.partyId) != null) GuestIcon() else if (member?.userId == null) NotInAppIcon() else Avatar(name, member.pictureUrl, decorative = true)
            Text(name)
        }
    }
}

/**
 * What the artist gets from this event (their share and refunds), how much came, how much is still to come; never from
 * whom. Settling together (ITERATION_6 §11), what their partner holds for them, but none of the partner's own numbers.
 */
@Composable
fun ArtistPayout(context: EventContext) {
    val me = context.me ?: return
    val line = context.settlement.line(me.id) ?: return
    val partner = context.settlement.partners[me.id]?.let { partyName(context.band, context.event, it, "") }
    val kept = SettleTogether.keptByPartner(context.settlement, me.id)
    SectionTitle(stringResource(Res.string.artist_payout))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PayoutRow(stringResource(Res.string.artist_to_receive), line.target, context.currency)
            PayoutRow(stringResource(Res.string.artist_received), line.received - line.sent, context.currency)
            // Artists hold no money (ITERATION_4 §1.1), so what is still owed to them is just the negative delta. If they
            // got too much (or owe from before they were an artist), a lead settles it with whoever is owed.
            if (partner != null && kept.isPositive) PayoutRow(stringResource(Res.string.artist_kept_by_partner, partner), kept, context.currency)
            if (line.delta.isPositive) PayoutRow(stringResource(Res.string.artist_to_hand_back), line.delta, context.currency, bold = true)
            else PayoutRow(stringResource(Res.string.artist_still_to_receive), -line.delta - kept, context.currency, bold = true)
            if (partner != null) Text(stringResource(Res.string.artist_settles_with, partner), style = MaterialTheme.typography.bodySmall)
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
    SectionTitle(stringResource(Res.string.artist_my_costs))
    costs.forEach { cost ->
        val status = statuses.status(cost)
        Row(
            Modifier.fillMaxWidth().clickable { onOpenCost(cost.id) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CostIcon(cost, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost))
                Text(formatDate(cost.date, language), style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(cost.amount, context.currency)
                Text(costStatusText(band, cost, status), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (context.can.addCostIn(context.event)) TextButton(onClick = onAddCost) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(Res.string.add_cost), Modifier.padding(start = 8.dp))
    }
}
