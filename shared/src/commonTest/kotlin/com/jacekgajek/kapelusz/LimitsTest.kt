package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.UpdateEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitsTest {
    @Test
    fun amountsHaveAnUpperBoundAndTheLargestOneStillSettles() {
        val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
        val tooMuch = Money(Limits.MAX_AMOUNT + 1)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(AddHolding("gig", "h", "alice", tooMuch, IncomeSource.HAT, null)).reason)
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(AddCost("c", "alice", tooMuch, DATE)).reason)
        // The largest allowed amounts, many times over and with the largest share, settle without overflow.
        repeat(20) { scenario.apply(AddHolding("gig", "h$it", "alice", Money(Limits.MAX_AMOUNT), IncomeSource.HAT, null)) }
        scenario.apply(com.jacekgajek.kapelusz.ops.SetWeight("gig", "alice", 10_000))
        val settlement = SettlementCalculator.settle(scenario.band, scenario.band.event("gig")!!)
        assertEquals(Money(Limits.MAX_AMOUNT * 20), settlement.lines.fold(Money.ZERO) { sum, line -> sum + line.target })
    }

    @Test
    fun idsAreCheckedAndTheBandFundsIdIsReserved() {
        val scenario = Scenario("Alice").event("gig", "Alice")
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(CreateEvent("x\r\nURL:https://evil", "Gig", DATE)).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(AddGuest("gig", "band-fund", "Fund?")).reason)
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(AddMember("band-fund", "Fund?")).reason)
        scenario.apply(AddGuest("gig", "carol", "Carol"))
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(AddMember("carol", "Carol")).reason, "a guest's id")
    }

    @Test
    fun textsAreBounded() {
        val scenario = Scenario("Alice").event("gig", "Alice")
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(UpdateEvent("gig", name = Change("x".repeat(Limits.MAX_NAME + 1)))).reason)
        scenario.apply(UpdateEvent("gig", name = Change("x".repeat(Limits.MAX_NAME))))
        assertEquals(RejectionReason.INVALID_INPUT, scenario.rejected(AddCost("c", "alice", Money(100), DATE, description = "d".repeat(Limits.MAX_TEXT + 1))).reason)
    }

    @Test
    fun parsingRefusesNumbersThatWouldOverflow() {
        assertEquals(Money(999_999_999_999_900), Money.parse("9999999999999"))
        assertFailsWith<IllegalArgumentException> { Money.parse("99999999999999999") }
    }

    @Test
    fun aGuestCannotTakeAMembersIdAndOldLongTextsDoNotBlockOtherChanges() {
        val scenario = Scenario("Alice", "Bob").event("gig", "Alice")
        assertEquals(RejectionReason.DUPLICATE_ID, scenario.rejected(AddGuest("gig", "bob", "Bob?")).reason)
        // Notes longer than today's limit (from before it): other changes to the event still work.
        val long = scenario.band.copy(events = scenario.band.events.map { it.copy(notes = "n".repeat(Limits.MAX_NOTES + 10)) })
        val applied = com.jacekgajek.kapelusz.ops.Engine.apply(long, com.jacekgajek.kapelusz.auth.Actor.System, AddHolding("gig", "h", "alice", Money(100), IncomeSource.HAT, null), "op", 1)
        kotlin.test.assertIs<com.jacekgajek.kapelusz.ops.ApplyResult.Applied>(applied)
    }

    @Test
    fun negativeAmountsAreBoundedToo() {
        val scenario = Scenario("Alice").event("gig", "Alice").apply(com.jacekgajek.kapelusz.ops.SetTreasurer("alice"))
        assertEquals(RejectionReason.INVALID_AMOUNT, scenario.rejected(com.jacekgajek.kapelusz.ops.SetFundAmount("gig", Money(Long.MIN_VALUE))).reason)
    }

    @Test
    fun attachmentIdsAreUniqueInTheWholeBand() {
        val scenario = Scenario("Alice").apply(AddCost("c1", "alice", Money(100), DATE)).apply(AddCost("c2", "alice", Money(100), DATE))
        val file = com.jacekgajek.kapelusz.model.Attachment("a1", com.jacekgajek.kapelusz.model.AttachmentKind.IMAGE, "r.jpg", 10)
        scenario.apply(com.jacekgajek.kapelusz.ops.AddAttachment("c1", file))
        assertEquals(RejectionReason.DUPLICATE_ID, scenario.rejected(com.jacekgajek.kapelusz.ops.AddAttachment("c2", file)).reason)
    }
}
