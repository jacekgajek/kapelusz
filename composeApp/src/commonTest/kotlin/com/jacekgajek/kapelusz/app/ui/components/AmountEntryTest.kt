package com.jacekgajek.kapelusz.app.ui.components

import com.jacekgajek.kapelusz.model.Money
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** An amount as typed in a form: what it comes to. */
class AmountEntryTest :
    ExpectSpec({
        fun entry(initial: Money? = null) = AmountEntry(initial, amountText = { it.toString() })

        expect("starts from the amount given, as text") {
            val entry = entry(Money.parse("12.50"))
            entry.text shouldBe "12.50"
            entry.gross shouldBe Money.parse("12.50")
        }

        expect("starts empty without one") {
            val entry = entry()
            entry.text shouldBe ""
            entry.gross.shouldBeNull()
        }

        expect("the gross follows what is typed") {
            val entry = entry()
            entry.text = "1 200,50"
            entry.gross shouldBe Money.parse("1200.50")
            entry.text = "abc"
            entry.gross.shouldBeNull()
        }

        expect("nothing or less is no amount") {
            val entry = entry()
            entry.text = "0"
            entry.gross.shouldBeNull()
            entry.text = "-5"
            entry.gross.shouldBeNull()
        }
    })
