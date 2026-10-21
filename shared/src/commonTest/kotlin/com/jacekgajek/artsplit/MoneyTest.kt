package com.jacekgajek.artsplit

import com.jacekgajek.artsplit.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun parsesAndFormats() {
        assertEquals(Money(12960), Money.parse("129.60"))
        assertEquals(Money(12960), Money.parse("129,6"))
        assertEquals(Money(100000), Money.parse("1000"))
        assertEquals(Money(-5), Money.parse("-0.05"))
        assertEquals("129.60", Money(12960).toString())
        assertEquals("-0.05", Money(-5).toString())
        assertEquals("0.00", Money.ZERO.toString())
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { Money.parse("12.345") }
        assertFailsWith<IllegalArgumentException> { Money.parse("abc") }
    }
}
