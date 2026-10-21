package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.band_fund
import com.jacekgajek.artsplit.app.generated.line_gets
import com.jacekgajek.artsplit.app.generated.line_hands_over
import com.jacekgajek.artsplit.app.generated.line_holds
import com.jacekgajek.artsplit.app.generated.line_payout
import com.jacekgajek.artsplit.app.generated.line_payout_share_only
import com.jacekgajek.artsplit.app.generated.line_settled
import com.jacekgajek.artsplit.app.generated.settle
import com.jacekgajek.artsplit.app.generated.summary
import com.jacekgajek.artsplit.app.ui.components.SectionTitle
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.partyName
import com.jacekgajek.artsplit.app.ui.theme.MoneyColors
import com.jacekgajek.artsplit.domain.EventStatus
import com.jacekgajek.artsplit.domain.PartyLine
import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.ops.SettleSuggestions
import org.jetbrains.compose.resources.stringResource

/** Per party: what they should end up with (share + refunds), what they hold now, and the difference. */
@Composable
fun SummarySection(context: EventContext) {
    val lines = context.settlement.lines
    if (lines.isEmpty()) return
    SectionTitle(stringResource(Res.string.summary))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            lines.forEach { line -> SummaryRow(context, line) }
        }
    }
}

@Composable
private fun SummaryRow(context: EventContext, line: PartyLine) {
    val currency = context.currency
    val lang = language
    val fundLabel = stringResource(Res.string.band_fund)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(partyName(context.band, context.event, line.partyId, fundLabel), style = MaterialTheme.typography.titleSmall)
            if (line.partyId != BAND_FUND) {
                Text(
                    if (line.refunds.isZero) {
                        stringResource(Res.string.line_payout_share_only, formatMoney(line.target, currency, lang))
                    } else {
                        stringResource(
                            Res.string.line_payout,
                            formatMoney(line.target, currency, lang),
                            formatMoney(line.share, currency, lang),
                            formatMoney(line.refunds, currency, lang),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(Res.string.line_holds, formatMoney(line.balance, currency, lang)), style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
            DeltaLabel(context, line)
            if (context.settlement.canSettle && context.settlement.suggestionsFor(line.partyId).isNotEmpty() &&
                context.can.settleFor(context.event, line.partyId)
            ) {
                TextButton(onClick = { context.actions.run(SettleSuggestions(context.event.id, line.partyId)) }) {
                    Text(stringResource(Res.string.settle))
                }
            }
        }
    }
}

@Composable
private fun DeltaLabel(context: EventContext, line: PartyLine) {
    val currency = context.currency
    val lang = language
    when {
        context.settlement.status == EventStatus.DRAFT -> {}
        line.delta.isNegative -> Text(
            stringResource(Res.string.line_gets, formatMoney(-line.delta, currency, lang)),
            color = MoneyColors.positive,
            style = MaterialTheme.typography.labelLarge,
        )
        line.delta.isPositive -> Text(
            stringResource(Res.string.line_hands_over, formatMoney(line.delta, currency, lang)),
            color = MoneyColors.negative,
            style = MaterialTheme.typography.labelLarge,
        )
        else -> Text(stringResource(Res.string.line_settled), style = MaterialTheme.typography.labelLarge)
    }
}
