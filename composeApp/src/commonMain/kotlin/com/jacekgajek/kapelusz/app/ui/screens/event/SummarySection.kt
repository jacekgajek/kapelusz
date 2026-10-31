package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.line_gets
import com.jacekgajek.kapelusz.app.generated.line_hands_over
import com.jacekgajek.kapelusz.app.generated.line_holds
import com.jacekgajek.kapelusz.app.generated.line_keeps_for_fund
import com.jacekgajek.kapelusz.app.generated.line_payout
import com.jacekgajek.kapelusz.app.generated.line_payout_fixed
import com.jacekgajek.kapelusz.app.generated.line_payout_fixed_only
import com.jacekgajek.kapelusz.app.generated.line_payout_percent
import com.jacekgajek.kapelusz.app.generated.line_payout_percent_only
import com.jacekgajek.kapelusz.app.generated.line_payout_share_only
import com.jacekgajek.kapelusz.app.generated.line_settled
import com.jacekgajek.kapelusz.app.generated.pair_breakdown
import com.jacekgajek.kapelusz.app.generated.pair_names
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.summary
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.theme.MoneyColors
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.PartyLine
import com.jacekgajek.kapelusz.view.fundKeptBy
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

/**
 * Per party: what they should end up with (share + refunds), what they hold now, and the difference. A pair settling
 * together is one row, opened to show each partner's numbers (I5 §7).
 */
@Composable
fun SummarySection(context: EventContext) {
    val lines = context.settlement.parties
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
    val isPair = line.partnerId != null
    var open by remember(line.partyId) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = isPair) { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    partyName(context.band, context.event, context.settlement, line.partyId, fundLabel, stringResource(Res.string.pair_names)),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isPair) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (line.partyId != BAND_FUND) {
                // A fixed share says so (I3 §2.4): it does not change with the income. A percentage too (§6).
                val percent = line.percent?.let { "$it%" }
                Text(
                    when {
                        line.refunds.isZero && percent != null ->
                            stringResource(Res.string.line_payout_percent_only, formatMoney(line.target, currency, lang), percent)

                        line.refunds.isZero -> stringResource(
                            if (line.isFixed) Res.string.line_payout_fixed_only else Res.string.line_payout_share_only,
                            formatMoney(line.target, currency, lang),
                        )

                        percent != null -> stringResource(
                            Res.string.line_payout_percent,
                            formatMoney(line.target, currency, lang),
                            formatMoney(line.share, currency, lang),
                            formatMoney(line.refunds, currency, lang),
                            percent,
                        )

                        else -> stringResource(
                            if (line.isFixed) Res.string.line_payout_fixed else Res.string.line_payout,
                            formatMoney(line.target, currency, lang),
                            formatMoney(line.share, currency, lang),
                            formatMoney(line.refunds, currency, lang),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(Res.string.line_holds, formatMoney(line.balance, currency, lang)), style = MaterialTheme.typography.bodySmall)
            // Each partner's own numbers: their share and refunds, and what they hold.
            if (open) {
                line.partyIds.forEach { id ->
                    val own = context.settlement.line(id) ?: return@forEach
                    Text(
                        stringResource(
                            Res.string.pair_breakdown,
                            partyName(context.band, context.event, id, fundLabel),
                            formatMoney(own.target, currency, lang),
                            formatMoney(own.balance, currency, lang),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Information only (I2 §7.4): transfers are recorded in To settle, one "X → Y" at a time or all at once.
        Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
            DeltaLabel(context, line)
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

        line.delta.isPositive -> {
            // The treasurer doesn't hand the fund's part to anyone: they keep it. Said apart, so the red amount is only
            // what leaves their pocket.
            val keeps = fundKeptBy(context.band, context.settlement, line)
            val handsOver = line.delta - keeps
            if (handsOver.isPositive) {
                Text(
                    stringResource(Res.string.line_hands_over, formatMoney(handsOver, currency, lang)),
                    color = MoneyColors.negative,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (keeps.isPositive) {
                Text(
                    stringResource(Res.string.line_keeps_for_fund, formatMoney(keeps, currency, lang)),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        else -> Text(stringResource(Res.string.line_settled), style = MaterialTheme.typography.labelLarge)
    }
}
