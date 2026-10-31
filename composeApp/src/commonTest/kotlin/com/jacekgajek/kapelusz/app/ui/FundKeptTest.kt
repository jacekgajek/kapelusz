package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.model.fundKeptBy
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetTreasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

/** The treasurer's line keeps the fund's part rather than handing it over (ITERATION_6 §8). */
class FundKeptTest {

    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.System, op, "op", 0) as ApplyResult.Applied).band
    }

    private fun zl(amount: Int) = Money(amount * 100L)

    private fun kept(band: Band, partyId: String): Money {
        val settlement = SettlementCalculator.settle(band, band.event("gig")!!)
        return fundKeptBy(band, settlement, settlement.line(partyId)!!)
    }

    private val base = Band.create("b", "Band", "PLN", "me", "Me", "u").apply(
        AddMember("bob", "Bob"),
        SetTreasurer("me"),
        CreateEvent("gig", "Gig", LocalDate(2026, 10, 27), participants = listOf(Participant("me"), Participant("bob"))),
    )

    @Test
    fun theTreasurerHoldingTheMoneyKeepsTheFundsPart() {
        val band = base.apply(AddHolding("gig", "h", "me", zl(1000), IncomeSource.DOOR), SetFundAmount("gig", zl(100)))
        assertEquals(zl(100), kept(band, "me"), "hands over 450 to Bob, keeps 100")
    }

    @Test
    fun someoneElseHoldingTheMoneyKeepsNothing() {
        val band = base.apply(AddHolding("gig", "h", "bob", zl(1000), IncomeSource.DOOR), SetFundAmount("gig", zl(100)))
        assertEquals(Money.ZERO, kept(band, "bob"), "Bob hands the fund's part to the treasurer")
        assertEquals(Money.ZERO, kept(band, "me"), "the treasurer gets money, keeps nothing")
    }

    @Test
    fun nothingKeptWhenTheFundCoversPartOfTheGig() {
        val band = base.apply(AddHolding("gig", "h", "me", zl(1000), IncomeSource.DOOR), SetFundAmount("gig", zl(-100)))
        assertEquals(Money.ZERO, kept(band, "me"))
    }
}
