package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.Money

object VatMath {
    fun grossOf(net: Money, rate: Int): Money = Money(divideRounding(net.minor * (100 + rate), 100))

    fun netOf(gross: Money, rate: Int): Money = Money(divideRounding(gross.minor * 100, rate + 100L))

    fun vatOf(gross: Money, net: Money): Money = gross - net

    /**
     * Whether [gross] and [net] go together at [rate]. Rounding at the grosz makes the two derivations lossy, so a pair
     * typed from the net passes the first test and one typed from the gross the second; neither accepts a tampered pair.
     */
    fun matches(gross: Money, net: Money, rate: Int) = net.isPositive &&
        rate in 1..100 &&
        (grossOf(net, rate) == gross || netOf(gross, rate) == net)

    private fun divideRounding(a: Long, b: Long): Long = (a + b / 2) / b
}
