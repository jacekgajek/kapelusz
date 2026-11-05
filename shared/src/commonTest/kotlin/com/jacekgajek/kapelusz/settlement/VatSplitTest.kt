package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.CONTRACT
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Vat
import com.jacekgajek.kapelusz.model.VatSplitMode
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.pln
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** A fee with VAT in it: what the band splits, and where the VAT stays. */
class VatSplitTest :
    ExpectSpec({
        val net = Vat(pln("1000"), 23, VatSplitMode.NET)
        val gross = Vat(pln("1000"), 23, VatSplitMode.GROSS)

        /** Alice and Bob, equal shares; Alice holds the fee of 1230 with VAT. */
        fun gig(vat: Vat?) = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
            .apply(AddHolding("gig", "fee", "alice", pln("1230"), CONTRACT, vat = vat))

        expect("splitting the net: the VAT is not the band's money and stays with the holder") {
            val settlement = gig(net).settle("gig")
            settlement.income shouldBe pln("1000")
            settlement.line("alice")!!.collected shouldBe pln("1000")
            settlement.line("alice")!!.share shouldBe pln("500")
            withClue("Alice passes on half of the net, not of the gross") {
                settlement.suggestions shouldBe listOf(SuggestedTransfer("alice", "bob", pln("500")))
            }
        }

        expect("splitting the gross: the same as money without VAT") {
            val withVat = gig(gross).settle("gig")
            val plain = gig(null).settle("gig")
            withVat.income shouldBe pln("1230")
            withVat.lines shouldBe plain.lines
            withVat.suggestions shouldBe plain.suggestions
        }

        expect("an awaited fee with VAT counts the same before and after it comes in") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
                .apply(AddHolding("gig", "fee", ORGANISER, pln("1230"), CONTRACT, vat = net))
            scenario.settle("gig").income shouldBe pln("1000")

            scenario.apply(ReceiveAwaited("gig", "fee", "paid", "bob", pln("1230")))
            val paid = scenario.settle("gig")
            paid.income shouldBe pln("1000")
            paid.line("bob")!!.collected shouldBe pln("1000")
        }

        expect("the fund holding a net-split fee has the net, the VAT being owed on") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
                .apply(SetTreasurer("alice"))
                .apply(AddHolding("gig", "fee", ORGANISER, pln("1230"), CONTRACT, vat = net))
                .apply(ReceiveAwaited("gig", "fee", "paid", BAND_FUND, pln("1230")))
            scenario.settle("gig").line(BAND_FUND)!!.collected shouldBe pln("1000")
            withClue("as the settlement counts it, so the fund does not look richer than the event") {
                BandFund.balance(scenario.band) shouldBe pln("1000")
            }
        }
    })
