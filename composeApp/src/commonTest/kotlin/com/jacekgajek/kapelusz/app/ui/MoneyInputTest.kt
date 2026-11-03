package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.model.Money
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class MoneyInputTest :
    ExpectSpec({
        expect("thousands separators in either style") {
            parseMoney("1,000") shouldBe Money(100_000)
            parseMoney("1.000") shouldBe Money(100_000)
            parseMoney("1,000.50") shouldBe Money(100_050)
            parseMoney("1.000,50") shouldBe Money(100_050)
            parseMoney("1,000,000") shouldBe Money(100_000_000)
            parseMoney("12,5") shouldBe Money(1_250)
            parseMoney("12.50") shouldBe Money(1_250)
            parseMoney("1 000") shouldBe Money(100_000)
        }
    })
