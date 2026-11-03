package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.view.fundKeptBy
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
    (Engine.apply(band, Actor.System, op, "op", 0) as ApplyResult.Applied).band
}

private fun zl(amount: Int) = Money(amount * 100L)

private fun kept(band: Band, partyId: String): Money {
    val settlement = SettlementCalculator.settle(band, band.event("gig")!!)
    return fundKeptBy(band, settlement, settlement.line(partyId)!!)
}

/** The treasurer's line keeps the fund's part rather than handing it over. */
class FundKeptTest :
    ExpectSpec({
        val base = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
            AddMember("bob", "Bob"),
            SetTreasurer("me"),
            CreateEvent("gig", "Gig", LocalDate(2026, 10, 27), participants = listOf(Participant("me"), Participant("bob"))),
        )

        expect("the treasurer holding the money keeps the fund's part") {
            val band = base.apply(AddHolding("gig", "h", "me", zl(1000), IncomeSource.DOOR), SetFundAmount("gig", zl(100)))
            withClue("hands over 450 to Bob, keeps 100") { kept(band, "me") shouldBe zl(100) }
        }

        expect("someone else holding the money keeps nothing") {
            val band = base.apply(AddHolding("gig", "h", "bob", zl(1000), IncomeSource.DOOR), SetFundAmount("gig", zl(100)))
            withClue("Bob hands the fund's part to the treasurer") { kept(band, "bob") shouldBe Money.ZERO }
            withClue("the treasurer gets money, keeps nothing") { kept(band, "me") shouldBe Money.ZERO }
        }

        expect("nothing kept when the fund covers part of the gig") {
            val band = base.apply(AddHolding("gig", "h", "me", zl(1000), IncomeSource.DOOR), SetFundAmount("gig", zl(-100)))
            kept(band, "me") shouldBe Money.ZERO
        }
    })
