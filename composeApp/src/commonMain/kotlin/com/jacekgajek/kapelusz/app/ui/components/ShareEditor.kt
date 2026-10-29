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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.member_share_fixed
import com.jacekgajek.kapelusz.app.generated.share_by_percent
import com.jacekgajek.kapelusz.app.generated.share_by_weight
import com.jacekgajek.kapelusz.app.generated.share_count
import com.jacekgajek.kapelusz.app.generated.share_fixed_amount
import com.jacekgajek.kapelusz.app.generated.share_fixed_hint
import com.jacekgajek.kapelusz.app.generated.share_none
import com.jacekgajek.kapelusz.app.generated.share_of_profit
import com.jacekgajek.kapelusz.app.generated.share_part
import com.jacekgajek.kapelusz.app.generated.share_parts
import com.jacekgajek.kapelusz.app.generated.share_percent_hint
import com.jacekgajek.kapelusz.app.generated.share_percent_over
import com.jacekgajek.kapelusz.app.generated.share_percent_taken
import com.jacekgajek.kapelusz.app.generated.share_weight_hint
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.SharePercent
import com.jacekgajek.kapelusz.ops.Limits
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How a share is given: a number of shares (a weight), a percentage of the profit, or a fixed amount. */
enum class ShareMode { WEIGHT, PERCENT, FIXED }

/**
 * What a share editor holds (ITERATION_3 §2.4, ITERATION_7 §2h): a weight, a percentage of the profit, or a fixed
 * amount being typed. The weight stays whatever the slider says in every mode: it is kept underneath for switching back.
 */
@Stable
class ShareEditorState(mode: ShareMode, weight: Int, percent: Int, amount: String) {
    var mode by mutableStateOf(mode)
    var weight by mutableStateOf(weight)
    var percent by mutableStateOf(percent)
    var amount by mutableStateOf(amount)

    /** The typed fixed amount, when valid. */
    val parsedAmount: Money? get() = parseMoney(amount)?.takeIf { it.isPositive && it.minor <= Limits.MAX_AMOUNT }

    /** Ready to save: shares and a percentage always are; a fixed share needs a valid amount. */
    val valid: Boolean get() = mode != ShareMode.FIXED || parsedAmount != null

    /** [participant] with this share, as the reducer stores it: 0% is no share, a weight of 0. Only when [valid]. */
    fun applyTo(participant: Participant): Participant = when (mode) {
        ShareMode.WEIGHT -> participant.copy(weight = weight, fixedShare = null, percent = null)
        ShareMode.PERCENT -> if (percent == 0) participant.copy(weight = 0, fixedShare = null, percent = null)
            else participant.copy(weight = weight, fixedShare = null, percent = percent)
        ShareMode.FIXED -> participant.copy(weight = weight, fixedShare = parsedAmount, percent = null)
    }
}

@Composable
fun rememberShareEditorState(weight: Int, fixedShare: Money?, percent: Int?): ShareEditorState {
    val lang = language
    return remember {
        val mode = when {
            fixedShare != null -> ShareMode.FIXED
            percent != null -> ShareMode.PERCENT
            else -> ShareMode.WEIGHT
        }
        ShareEditorState(mode, weight, percent ?: DEFAULT_PERCENT, fixedShare?.let { formatMoneyInput(it, lang) } ?: "")
    }
}

/**
 * A share as a number of shares on a slider in quarters, a percentage of the profit in 5% steps (ITERATION_7 §2h), or a
 * fixed amount: in an event (ITERATION_3 §2.4) and as a member's default (ITERATION_4 §2.3). [below] shows under the
 * slider, e.g. what the share comes to; [percentTaken] is what the others' percentages and the fund's already take.
 */
@Composable
fun ShareEditor(state: ShareEditorState, currency: String, percentTaken: Int = 0, below: @Composable () -> Unit = {}) {
    val currencyLabel = if (language == "pl" && currency == "PLN") "zł" else currency
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(
                Triple(ShareMode.WEIGHT, stringResource(Res.string.share_by_weight), stringResource(Res.string.share_by_weight)),
                Triple(ShareMode.PERCENT, "%", stringResource(Res.string.share_by_percent)),
                Triple(ShareMode.FIXED, currencyLabel, stringResource(Res.string.share_fixed_amount)),
            )
            modes.forEachIndexed { index, (mode, label, description) ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { state.mode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = {},
                    modifier = Modifier.semantics { contentDescription = description },
                ) { Text(label) }
            }
        }
        when (state.mode) {
            ShareMode.WEIGHT -> {
                Text(sharesText(state.weight), style = MaterialTheme.typography.titleMedium)
                // A weight off the steps (e.g. 110 from before) shows as is; the slider snaps it only when moved.
                Slider(
                    value = state.weight.coerceIn(0, MAX_SLIDER_WEIGHT).toFloat(),
                    onValueChange = { state.weight = (it / WEIGHT_STEP).roundToInt() * WEIGHT_STEP },
                    valueRange = 0f..MAX_SLIDER_WEIGHT.toFloat(),
                    steps = MAX_SLIDER_WEIGHT / WEIGHT_STEP - 1,
                )
                SliderMarks((0..MAX_SLIDER_WEIGHT step DEFAULT_WEIGHT / 2).map { sharesNumber(it, language) })
                below()
                Text(stringResource(Res.string.share_weight_hint), style = MaterialTheme.typography.bodySmall)
            }
            ShareMode.PERCENT -> {
                Text(stringResource(Res.string.share_of_profit, "${state.percent}%"), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = state.percent.coerceIn(0, SharePercent.MAX).toFloat(),
                    onValueChange = { state.percent = (it / PERCENT_STEP).roundToInt() * PERCENT_STEP },
                    valueRange = 0f..SharePercent.MAX.toFloat(),
                    steps = SharePercent.MAX / PERCENT_STEP - 1,
                )
                SliderMarks((0..SharePercent.MAX step SharePercent.MAX / 4).map { "$it%" })
                below()
                if (percentTaken > 0) {
                    val over = state.percent + percentTaken > SharePercent.MAX
                    Text(
                        stringResource(if (over) Res.string.share_percent_over else Res.string.share_percent_taken, "$percentTaken%"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(Res.string.share_percent_hint), style = MaterialTheme.typography.bodySmall)
            }
            ShareMode.FIXED -> {
                MoneyField(state.amount, { state.amount = it }, stringResource(Res.string.share_fixed_amount))
                Text(stringResource(Res.string.share_fixed_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Labels evenly under a slider's track, the first at its start and the last at its end; read out by the slider instead. */
@Composable
private fun SliderMarks(labels: List<String>) {
    Layout(
        content = { labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val inset = SLIDER_THUMB_INSET.roundToPx()
        val span = constraints.maxWidth - 2 * inset
        layout(constraints.maxWidth, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { index, placeable ->
                val center = inset + span * index / (placeables.size - 1).coerceAtLeast(1)
                placeable.place((center - placeable.width / 2).coerceIn(0, constraints.maxWidth - placeable.width), 0)
            }
        }
    }
}

/** "1½", "¾", "2", "1.1" (Polish "1,1"): a weight as a number of shares, 100 being one. */
fun sharesNumber(weight: Int, language: String): String {
    val whole = weight / DEFAULT_WEIGHT
    val part = weight % DEFAULT_WEIGHT
    val quarter = QUARTERS[part] ?: return "$whole${if (language == "pl") "," else "."}${part.toString().padStart(2, '0').trimEnd('0')}"
    return if (whole == 0 && part != 0) quarter else "$whole$quarter"
}

/** "1½ shares", "1 share", "No share": a weight in words (ITERATION_7 §2h). */
@Composable
fun sharesText(weight: Int): String {
    val number = sharesNumber(weight, language)
    return when {
        weight == 0 -> stringResource(Res.string.share_none)
        weight % DEFAULT_WEIGHT == 0 -> (weight / DEFAULT_WEIGHT).let { pluralStringResource(Res.plurals.share_count, it, it.toString()) }
        weight < DEFAULT_WEIGHT && weight % WEIGHT_STEP == 0 -> stringResource(Res.string.share_part, number)
        else -> stringResource(Res.string.share_parts, number)
    }
}

/** A share in words: "1½ shares", "50% of profit" or "300 zł (fixed)"; null for a normal share, which goes unsaid. */
@Composable
fun shareText(weight: Int, fixedShare: Money?, percent: Int?, currency: String): String? = when {
    fixedShare != null -> stringResource(Res.string.member_share_fixed, formatMoney(fixedShare, currency, language))
    percent != null -> stringResource(Res.string.share_of_profit, "$percent%")
    weight != DEFAULT_WEIGHT -> sharesText(weight)
    else -> null
}

private val QUARTERS = mapOf(0 to "", 25 to "¼", 50 to "½", 75 to "¾")
private const val WEIGHT_STEP = 25
private const val MAX_SLIDER_WEIGHT = 200
private const val PERCENT_STEP = 5
private const val DEFAULT_PERCENT = 10

/** About half the thumb: where the track's ends are, for [SliderMarks]. */
private val SLIDER_THUMB_INSET = 10.dp
