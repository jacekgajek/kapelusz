package com.jacekgajek.kapelusz.model

import com.jacekgajek.kapelusz.model.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class MoneyTest :
    ExpectSpec({
        expect("parses and formats") {
            Money.parse("129.60") shouldBe Money(12960)
            Money.parse("129,6") shouldBe Money(12960)
            Money.parse("1000") shouldBe Money(100000)
            Money.parse("-0.05") shouldBe Money(-5)
            Money(12960).toString() shouldBe "129.60"
            Money(-5).toString() shouldBe "-0.05"
            Money.ZERO.toString() shouldBe "0.00"
        }

        expect("rejects garbage") {
            shouldThrow<IllegalArgumentException> { Money.parse("12.345") }
            shouldThrow<IllegalArgumentException> { Money.parse("abc") }
        }
    })
