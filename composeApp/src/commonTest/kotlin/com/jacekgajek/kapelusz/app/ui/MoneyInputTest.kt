package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyInputTest {
    @Test
    fun thousandsSeparatorsInEitherStyle() {
        assertEquals(Money(100_000), parseMoney("1,000"))
        assertEquals(Money(100_000), parseMoney("1.000"))
        assertEquals(Money(100_050), parseMoney("1,000.50"))
        assertEquals(Money(100_050), parseMoney("1.000,50"))
        assertEquals(Money(100_000_000), parseMoney("1,000,000"))
        assertEquals(Money(1_250), parseMoney("12,5"))
        assertEquals(Money(1_250), parseMoney("12.50"))
        assertEquals(Money(100_000), parseMoney("1 000"))
    }
}
