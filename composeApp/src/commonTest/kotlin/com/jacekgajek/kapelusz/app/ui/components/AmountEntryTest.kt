package com.jacekgajek.kapelusz.app.ui.components

import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Vat
import com.jacekgajek.kapelusz.model.VatSplitMode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** An amount as typed in a form: what it comes to. */
class AmountEntryTest :
    ExpectSpec({
        fun entry(initial: Money? = null, vat: Vat? = null) = AmountEntry(initial, vat, text = { it.toString() })

        expect("starts from the amount given, as text") {
            val entry = entry(Money.parse("12.50"))
            entry.grossText shouldBe "12.50"
            entry.gross shouldBe Money.parse("12.50")
        }

        expect("starts empty without one") {
            val entry = entry()
            entry.grossText shouldBe ""
            entry.gross.shouldBeNull()
        }

        expect("the gross follows what is typed") {
            val entry = entry()
            entry.typeGross("1 200,50")
            entry.gross shouldBe Money.parse("1200.50")
            entry.typeGross("abc")
            entry.gross.shouldBeNull()
        }

        expect("nothing or less is no amount") {
            val entry = entry()
            entry.typeGross("0")
            entry.gross.shouldBeNull()
            entry.typeGross("-5")
            entry.gross.shouldBeNull()
        }

        expect("a plain amount has no VAT, whatever is left in the other fields") {
            val entry = entry()
            entry.typeGross("123")
            entry.valid shouldBe true
            entry.vat.shouldBeNull()

            entry.withVat = true
            withClue("the net and the rate are still missing") { entry.valid shouldBe false }
            entry.typeVat("23")
            entry.typeNet("100")
            entry.vat shouldBe Vat(Money.parse("100"), 23, VatSplitMode.NET)
            entry.valid shouldBe true

            entry.withVat = false
            entry.vat.shouldBeNull()
            entry.valid shouldBe true
        }

        expect("typing one figure derives the other, and the rate re-derives the one not typed") {
            val entry = entry()
            entry.withVat = true
            entry.typeVat("23")
            entry.typeNet("1000")
            entry.grossText shouldBe "1230.00"

            entry.typeGross("1230.50")
            entry.netText shouldBe "1000.41"

            entry.typeVat("8")
            withClue("the gross was typed last, so it stays") { entry.grossText shouldBe "1230.50" }
            entry.netText shouldBe "1139.35"
            entry.vat shouldBe Vat(Money.parse("1139.35"), 8, VatSplitMode.NET)
        }

        expect("an existing VAT breakdown opens with VAT on, its figures and its split") {
            val entry = entry(Money.parse("1230"), Vat(Money.parse("1000"), 23, VatSplitMode.GROSS))
            entry.withVat shouldBe true
            entry.netText shouldBe "1000.00"
            entry.vatText shouldBe "23"
            entry.split shouldBe VatSplitMode.GROSS
            entry.vat shouldBe Vat(Money.parse("1000"), 23, VatSplitMode.GROSS)
        }
    })
