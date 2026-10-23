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
import com.jacekgajek.kapelusz.app.generated.share_by_percent
import com.jacekgajek.kapelusz.app.generated.share_fixed_amount
import com.jacekgajek.kapelusz.app.generated.share_fixed_hint
import com.jacekgajek.kapelusz.app.generated.share_weight_hint
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.Limits
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/** What a share editor holds: a percentage weight, or a fixed amount being typed (ITERATION_3 §2.4). */
@Stable
class ShareEditorState(fixed: Boolean, weight: Int, amount: String) {
    var fixed by mutableStateOf(fixed)
    var weight by mutableStateOf(weight)
    var amount by mutableStateOf(amount)

    /** The typed fixed amount, when valid. */
    val parsedAmount: Money? get() = parseMoney(amount)?.takeIf { it.isPositive && it.minor <= Limits.MAX_AMOUNT }

    /** Ready to save: a percentage always is; a fixed share needs a valid amount. */
    val valid: Boolean get() = !fixed || parsedAmount != null
}

@Composable
fun rememberShareEditorState(weight: Int, fixedShare: Money?): ShareEditorState {
    val lang = language
    return remember { ShareEditorState(fixedShare != null, weight, fixedShare?.let { formatMoneyInput(it, lang) } ?: "") }
}

/**
 * A share as a percentage on a slider in 25% steps, or a fixed amount: in an event (ITERATION_3 §2.4) and as a
 * member's default (ITERATION_4 §2.3). [below] shows under the slider, e.g. what the percentage comes to.
 */
@Composable
fun ShareEditor(state: ShareEditorState, currency: String, below: @Composable () -> Unit = {}) {
    val currencyLabel = if (language == "pl" && currency == "PLN") "zł" else currency
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "%", true to currencyLabel).forEachIndexed { index, (value, label) ->
                val description = stringResource(if (value) Res.string.share_fixed_amount else Res.string.share_by_percent)
                SegmentedButton(
                    selected = state.fixed == value,
                    onClick = { state.fixed = value },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.semantics { contentDescription = description },
                ) { Text(label) }
            }
        }
        if (state.fixed) {
            MoneyField(state.amount, { state.amount = it }, stringResource(Res.string.share_fixed_amount))
            Text(stringResource(Res.string.share_fixed_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            Text("${state.weight}%", style = MaterialTheme.typography.titleMedium)
            // A weight off the steps (e.g. 110% from before) shows as is; the slider snaps it only when moved.
            Slider(
                value = state.weight.coerceIn(0, MAX_SLIDER_WEIGHT).toFloat(),
                onValueChange = { state.weight = (it / WEIGHT_STEP).roundToInt() * WEIGHT_STEP },
                valueRange = 0f..MAX_SLIDER_WEIGHT.toFloat(),
                steps = MAX_SLIDER_WEIGHT / WEIGHT_STEP - 1,
            )
            below()
            Text(stringResource(Res.string.share_weight_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val WEIGHT_STEP = 25
private const val MAX_SLIDER_WEIGHT = 200
