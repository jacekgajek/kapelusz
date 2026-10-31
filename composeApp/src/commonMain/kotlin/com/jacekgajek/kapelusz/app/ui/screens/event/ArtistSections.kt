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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_cost
import com.jacekgajek.kapelusz.app.generated.artist_held_for_partner
import com.jacekgajek.kapelusz.app.generated.artist_kept_by_partner
import com.jacekgajek.kapelusz.app.generated.artist_my_costs
import com.jacekgajek.kapelusz.app.generated.artist_payout
import com.jacekgajek.kapelusz.app.generated.artist_received
import com.jacekgajek.kapelusz.app.generated.artist_settled_with
import com.jacekgajek.kapelusz.app.generated.artist_settles_with
import com.jacekgajek.kapelusz.app.generated.artist_still_to_receive
import com.jacekgajek.kapelusz.app.generated.artist_to_hand_back
import com.jacekgajek.kapelusz.app.generated.artist_to_receive
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.participants
import com.jacekgajek.kapelusz.app.generated.settle_paid
import com.jacekgajek.kapelusz.app.generated.settle_part
import com.jacekgajek.kapelusz.app.generated.settle_received
import com.jacekgajek.kapelusz.app.generated.to_settle
import com.jacekgajek.kapelusz.app.generated.transfer_recorded
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.CostIcon
import com.jacekgajek.kapelusz.app.ui.components.GuestIcon
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.NotInAppIcon
import com.jacekgajek.kapelusz.app.ui.components.PartialSettleDialog
import com.jacekgajek.kapelusz.app.ui.components.PaymentButtons
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.TransferParties
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.screens.costStatusText
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.settlement.Payees
import com.jacekgajek.kapelusz.settlement.SettleTogether
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

// What an artist sees of an event they play (ITERATION_4 §1.1): who plays, their own payout, their own transfers
// (ITERATION_7 §3.2) and their own costs; none of the band's money.

/** Who plays: names only, no weights or shares. */
@Composable
fun ArtistLineup(context: EventContext) {
    val event = context.event
    SectionTitle(stringResource(Res.string.participants))
    SettlementCalculator.partyOrder(context.band, event).mapNotNull { event.participant(it) }.forEach { participant ->
        val name = partyName(context.band, event, participant.partyId, "")
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val member = context.band.member(participant.partyId)
            if (event.guest(participant.partyId) != null) {
                GuestIcon()
            } else if (member?.userId == null) {
                NotInAppIcon()
            } else {
                Avatar(name, member.pictureUrl, decorative = true)
            }
            Text(name)
        }
    }
}

/**
 * What the artist gets from this event (their share and refunds), how much came, how much is still to come; from whom is
 * [ArtistTransfers]. Settling together (ITERATION_6 §11), what their partner holds for them, but none of the partner's own numbers.
 */
@Composable
fun ArtistPayout(context: EventContext) {
    val me = context.me ?: return
    val line = context.settlement.line(me.id) ?: return
    val partnerId = context.settlement.partners[me.id]
    val partner = partnerId?.let { partyName(context.band, context.event, it, "") }
    // Either way round: the partner holds what the artist is owed, or the artist got what the partner is owed.
    val kept = SettleTogether.keptByPartner(context.settlement, me.id)
    val heldForPartner = partnerId?.let { SettleTogether.keptByPartner(context.settlement, it) } ?: Money.ZERO
    SectionTitle(stringResource(Res.string.artist_payout))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PayoutRow(stringResource(Res.string.artist_to_receive), line.target, context.currency)
            PayoutRow(stringResource(Res.string.artist_received), line.received - line.sent, context.currency)
            // Artists hold no money (ITERATION_4 §1.1), so what is still owed to them is just the negative delta. If they
            // got too much (or owe from before they were an artist), they hand it back (To settle, ITERATION_7 §3.2).
            if (partner != null && kept.isPositive) PayoutRow(stringResource(Res.string.artist_kept_by_partner, partner), kept, context.currency)
            if (partner != null && heldForPartner.isPositive) PayoutRow(stringResource(Res.string.artist_held_for_partner, partner), heldForPartner, context.currency)
            if (line.delta > heldForPartner) {
                PayoutRow(stringResource(Res.string.artist_to_hand_back), line.delta - heldForPartner, context.currency, bold = true)
            } else {
                PayoutRow(stringResource(Res.string.artist_still_to_receive), (-line.delta - kept).coerceAtLeast(Money.ZERO), context.currency, bold = true)
            }
            // A pair kept by an event settled together before it ended (ITERATION_5 §7) is told in the past.
            if (partner != null) {
                val text = if (me.settlesWith == partnerId) Res.string.artist_settles_with else Res.string.artist_settled_with
                Text(stringResource(text, partner), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The artist's own transfers still to make (ITERATION_7 §3.2): who pays them, or whom they hand back to with that person's
 * BLIK number and the amount to copy; nobody else's. They mark each paid or received themselves, as members do.
 */
@Composable
fun ArtistTransfers(context: EventContext) {
    val me = context.me ?: return
    val mine = Artists.ownTransfers(context.band, context.settlement, me.id)
    if (mine.isEmpty()) return
    SectionTitle(stringResource(Res.string.to_settle))
    mine.forEach { part ->
        key(part.from, part.to) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ArtistTransferRow(context, me.id, part) }
            }
        }
    }
}

/** One of [ArtistTransfers], laid out as a suggestion in To settle: the names, then the amount and the buttons. */
@Composable
private fun ArtistTransferRow(context: EventContext, me: Id, part: SuggestedTransfer) {
    val band = context.band
    val fundLabel = stringResource(Res.string.band_fund)
    val from = partyName(band, context.event, part.from, fundLabel)
    val to = partyName(band, context.event, part.to, fundLabel)
    // Only to the one who pays (ITERATION_5 §8), as in To settle.
    val pays = part.from == me
    val label = if (pays) Res.string.settle_paid else Res.string.settle_received
    var partial by remember { mutableStateOf(false) }
    val lang = language
    // Read without arguments, so its placeholders stay for the amount known only on tap (as in SuggestionRow).
    val recordedText = stringResource(Res.string.transfer_recorded)
    fun record(amount: Money) = context.actions.runWithUndo(
        RecordTransfer(context.event.id, newId(), part.from, part.to, amount),
        recordedText.replace("%1\$s", from).replace("%2\$s", to).replace("%3\$s", formatMoney(amount, context.currency, lang)),
    )
    val settles = context.can.recordTransfer(context.event, part.from, part.to)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransferParties(from, to, Modifier.weight(1f))
            if (pays) PaymentButtons(Payees.forHandOver(band, part.to))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                CopyAmountButton(context, part.amount)
                MoneyText(part.amount, context.currency, bold = true)
            }
            if (settles) {
                TextButton(onClick = { partial = true }) { Text(stringResource(Res.string.settle_part)) }
                FilledTonalButton(onClick = { record(part.amount) }) { Text(stringResource(label)) }
            }
        }
    }
    if (partial) {
        PartialSettleDialog(
            from = from,
            to = to,
            suggested = part.amount,
            currency = context.currency,
            onConfirm = { record(it) },
            onDismiss = { partial = false },
            confirmLabel = label,
            actions = context.actions,
        )
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
    val statuses = context.view.costStatuses
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
    if (context.can.addCostIn(context.event)) {
        TextButton(onClick = onAddCost) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(Res.string.add_cost), Modifier.padding(start = 8.dp))
        }
    }
}
