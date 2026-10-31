package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.amount_invalid
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.theme.MoneyColors
import com.jacekgajek.kapelusz.model.Money
import org.jetbrains.compose.resources.stringResource

/** The band's currency, for money shown without saying whose: provided while a band is open. */
val LocalCurrency = staticCompositionLocalOf<String> { error("No band open") }

@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    currency: String = LocalCurrency.current,
    colored: Boolean = false,
    bold: Boolean = false,
) {
    val color = when {
        !colored -> Color.Unspecified
        money.isNegative -> MoneyColors.negative
        money.isPositive -> MoneyColors.positive
        else -> Color.Unspecified
    }
    Text(
        formatMoney(money, currency, language),
        modifier,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        maxLines = 1,
    )
}

/** Decimal input for amounts; [onValid] receives the parsed amount or null when the text is not a valid amount. */
@Composable
fun MoneyField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    val parsed = parseMoney(text)
    val invalid = text.isNotBlank() && (parsed == null || (!allowNegative && parsed.isNegative))
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(Res.string.amount_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

fun parseMoney(text: String): Money? = runCatching { Money.parse(withoutGrouping(text.filterNot { it.isWhitespace() || it == '\u00A0' || it == '\u202F' })) }.getOrNull()

/**
 * Drops thousands separators in either style: "1,000", "1.000", "1,000.50", "1.000,50". Money has at
 * most 2 decimals, so a separator followed by exactly 3 digits, or one that repeats, or the first of two different
 * ones, groups thousands; what is left is the decimal separator.
 */
internal fun withoutGrouping(text: String): String {
    val separators = text.filter { it == ',' || it == '.' }
    if (separators.isEmpty()) return text
    val decimal: Char? = when {
        separators.toSet().size == 2 -> separators.last()
        separators.length > 1 -> null
        Regex("""[.,]\d{3}$""").containsMatchIn(text) -> null
        else -> separators.single()
    }
    return text.filter { (it != ',' && it != '.') || it == decimal }
}
