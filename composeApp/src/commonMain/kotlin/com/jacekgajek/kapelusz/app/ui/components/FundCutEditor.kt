package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.fund_amount_to_hint
import com.jacekgajek.kapelusz.app.generated.fund_percent_hint
import com.jacekgajek.kapelusz.app.generated.fund_percent_no_profit
import com.jacekgajek.kapelusz.app.generated.fund_percent_of_profit
import com.jacekgajek.kapelusz.app.generated.share_by_percent
import com.jacekgajek.kapelusz.app.generated.share_fixed_amount
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.Limits
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * What a band fund editor holds (ITERATION_7 §5.2): a percentage of the profit, or an amount being typed. Its own
 * editor, not a member's share: no shares, and its percentage stops at 50% in whole units.
 */
@Stable
class FundCutEditorState(byPercent: Boolean, percent: Int, amount: String) {
    var byPercent by mutableStateOf(byPercent)
    var percent by mutableStateOf(percent)
    var amount by mutableStateOf(amount)

    /** The typed amount, when valid; empty is nothing. */
    val parsedAmount: Money?
        get() = if (amount.isBlank()) Money.ZERO else parseMoney(amount)?.takeIf { !it.isNegative && it.minor <= Limits.MAX_AMOUNT }

    /** Ready to save: a percentage always is; an amount needs to be valid. */
    val valid: Boolean get() = byPercent || parsedAmount != null

    /** The cut to the fund chosen, null for nothing (0% or no amount); only when [valid]. */
    val cut: FundCut?
        get() = if (byPercent) {
            FundCut(percent = percent).takeIf { percent > 0 }
        } else {
            parsedAmount?.takeIf { it.isPositive }?.let { FundCut(amount = it) }
        }
}

@Composable
fun rememberFundCutEditorState(cut: FundCut?): FundCutEditorState {
    val lang = language
    return remember {
        FundCutEditorState(cut?.percent != null, cut?.percent ?: DEFAULT_PERCENT, cut?.amount?.let { formatMoneyInput(it, lang) } ?: "")
    }
}

/**
 * Money to the band fund as a percentage of the profit on a slider in 5% steps up to 50%, or a fixed amount
 * (ITERATION_7 §5.2). [profit] (in an event) shows what the percentage comes to; null where there is none (a default).
 */
@Composable
fun FundCutEditor(state: FundCutEditorState, currency: String, profit: Money?) {
    val lang = language
    val currencyLabel = if (lang == "pl" && currency == "PLN") "zł" else currency
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(true to "%", false to currencyLabel).forEachIndexed { index, (value, label) ->
                val description = stringResource(if (value) Res.string.share_by_percent else Res.string.share_fixed_amount)
                SegmentedButton(
                    selected = state.byPercent == value,
                    onClick = { state.byPercent = value },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = description },
                ) { Text(label) }
            }
        }
        if (state.byPercent) {
            Text("${state.percent}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = state.percent.coerceIn(0, MAX_SLIDER_PERCENT).toFloat(),
                onValueChange = { state.percent = (it / PERCENT_STEP).roundToInt() * PERCENT_STEP },
                valueRange = 0f..MAX_SLIDER_PERCENT.toFloat(),
                steps = MAX_SLIDER_PERCENT / PERCENT_STEP - 1,
            )
            if (profit != null && state.percent > 0) {
                Text(
                    if (profit.isPositive) {
                        stringResource(
                            Res.string.fund_percent_of_profit,
                            formatMoney(FundCut.ofProfit(state.percent, profit), currency, lang),
                            formatMoney(profit, currency, lang),
                        )
                    } else {
                        stringResource(Res.string.fund_percent_no_profit)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(Res.string.fund_percent_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            MoneyField(state.amount, { state.amount = it }, stringResource(Res.string.amount))
            Text(stringResource(Res.string.fund_amount_to_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val DEFAULT_PERCENT = 10
private const val PERCENT_STEP = 5
private const val MAX_SLIDER_PERCENT = 50
