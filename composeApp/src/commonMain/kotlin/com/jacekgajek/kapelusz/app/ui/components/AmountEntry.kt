package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Vat
import com.jacekgajek.kapelusz.model.VatSplitMode
import com.jacekgajek.kapelusz.settlement.VatMath
import com.jacekgajek.kapelusz.settlement.VatMath.grossOf
import com.jacekgajek.kapelusz.settlement.VatMath.netOf

/**
 * An amount of money as the user types it in a form: a plain amount, or [withVat] a gross with its net and VAT rate,
 * and which of the two the band splits. The forms read only [gross] (the money that changes hands), [vat] and
 * [valid], so how it is entered can change without them.
 */
@Stable
class AmountEntry(initialGross: Money?, initialVat: Vat?, private val text: (Money) -> String, defaultVat: Vat? = null) {
    private var vatOn by mutableStateOf(initialVat != null)

    /** Switched on, the rate and the split offered are the band's last ([defaultVat]), and the figure not typed follows the one typed. */
    var withVat: Boolean
        get() = vatOn
        set(value) {
            vatOn = value
            if (value) derive()
        }
    var split by mutableStateOf((initialVat ?: defaultVat)?.split ?: VatSplitMode.NET)
    var grossText by mutableStateOf(initialGross?.let(text) ?: "")
        private set
    var netText by mutableStateOf(initialVat?.net?.let(text) ?: "")
        private set
    var vatText by mutableStateOf((initialVat ?: defaultVat)?.rate?.toString() ?: "")
        private set

    /** The figure the user typed last: it stays, and the other follows, when the rate changes. */
    private var lastTyped by mutableStateOf(BaseFigure.GROSS)

    private enum class BaseFigure { GROSS, NET }

    fun typeVat(input: String) {
        vatText = input
        derive()
    }

    /** The figure not typed, from the one typed and the rate, once both are there. */
    private fun derive() {
        when (lastTyped) {
            BaseFigure.GROSS -> gross?.let { g -> vatRate?.let { v -> netText = text(netOf(g, v)) } }
            BaseFigure.NET -> net?.let { n -> vatRate?.let { v -> grossText = text(grossOf(n, v)) } }
        }
    }

    fun typeGross(input: String) {
        lastTyped = BaseFigure.GROSS
        grossText = input
        gross?.let { validGross -> vatRate?.let { v -> netText = text(netOf(validGross, v)) } }
    }

    fun typeNet(input: String) {
        lastTyped = BaseFigure.NET
        netText = input
        net?.let { validNet -> vatRate?.let { v -> grossText = text(grossOf(validNet, v)) } }
    }

    /** What was typed, when it is money and more than nothing. */
    val gross: Money? by derivedStateOf {
        parseMoney(this@AmountEntry.grossText)?.takeIf { it.isPositive }
    }
    val net: Money? by derivedStateOf {
        parseMoney(this@AmountEntry.netText)?.takeIf { it.isPositive }
    }
    val vatRate: Int? by derivedStateOf {
        vatText.toIntOrNull()
    }

    /** The VAT breakdown, [withVat] and when the net and the rate are there and fit the gross; null for a plain amount. */
    val vat: Vat? by derivedStateOf {
        val gross = gross
        val net = net
        val rate = vatRate
        if (withVat && gross != null && net != null && rate != null) {
            Vat(net, rate, split).takeIf { VatMath.matches(gross, net, rate) }
        } else {
            null
        }
    }

    /** Whether what was typed makes an amount: a gross, and [withVat] its breakdown. */
    val valid: Boolean by derivedStateOf { gross != null && (!withVat || vat != null) }
}
