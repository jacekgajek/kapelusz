package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.Money
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class VatMathTest :
    ExpectSpec(
        {

            expect("gross is correctly calculated") {
                VatMath.grossOf(Money(1000_00), 23) shouldBe Money(1230_00)
                VatMath.grossOf(Money(99), 23) shouldBe Money(1_22)
                VatMath.grossOf(Money(98), 23) shouldBe Money(1_21)
                VatMath.grossOf(Money(1_00), 23) shouldBe Money(1_23)
                VatMath.grossOf(Money(1), 23) shouldBe Money(1)
                VatMath.grossOf(Money(1_00), 8) shouldBe Money(1_08)
                VatMath.grossOf(Money(99_99), 8) shouldBe Money(107_99)
                VatMath.grossOf(Money(1000_00), 0) shouldBe Money(1000_00)
            }

            expect("net and vat are correctly calculated") {
                VatMath.netOf(Money(1230_00), 23) shouldBe Money(1000_00)
                VatMath.netOf(Money(123_01), 23) shouldBe Money(100_01)
                VatMath.netOf(Money(100), 23) shouldBe Money(81)
                VatMath.netOf(Money(100_00), 8) shouldBe Money(92_59)
                VatMath.vatOf(Money(1230_00), Money(1000_00)) shouldBe Money(230_00)
            }

            expect("a pair typed from either figure matches, at every amount up to 5000") {
                // Rounding makes the derivations lossy: 0,03 gross at 23 % is 0,02 net, whose gross is 0,02 again.
                for (rate in listOf(23, 8, 5)) {
                    for (minor in 1L..5000_00L) {
                        val gross = Money(minor)
                        withClue("typed gross $gross at $rate %") { VatMath.matches(gross, VatMath.netOf(gross, rate), rate) shouldBe true }
                        val net = Money(minor)
                        withClue("typed net $net at $rate %") { VatMath.matches(VatMath.grossOf(net, rate), net, rate) shouldBe true }
                    }
                }
            }

            expect("a pair that fits neither way does not match") {
                VatMath.matches(Money(1230_00), Money(1000_01), 23) shouldBe false
                VatMath.matches(Money(1230_00), Money(1000_00), 8) shouldBe false
                VatMath.matches(Money(1230_00), Money(1000_00), 0) shouldBe false
                VatMath.matches(Money(1230_00), Money.ZERO, 23) shouldBe false
            }
        },
    )
