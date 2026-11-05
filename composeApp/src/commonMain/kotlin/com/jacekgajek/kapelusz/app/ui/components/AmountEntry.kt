package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.model.Money

/**
 * An amount of money as the user types it in a form: the text, and what it comes to. The forms read only
 * [gross] (the money that changes hands), so how it is entered can change without them.
 */
@Stable
class AmountEntry(initial: Money?, amountText: (Money) -> String) {
    var text by mutableStateOf(initial?.let(amountText) ?: "")

    /** What was typed, when it is money and more than nothing. */
    val gross: Money? by derivedStateOf { parseMoney(text)?.takeIf { it.isPositive } }
}
