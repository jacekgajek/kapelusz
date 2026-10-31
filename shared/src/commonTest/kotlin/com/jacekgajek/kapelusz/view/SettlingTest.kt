package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settle tab's lists, as [Settling] computes them for each role. */
class SettlingTest {
    // Alice (owner) collected 100 at a gig with Bob (member) and Carol (viewer, not playing): Alice hands 50 to Bob.
    private val scenario = Scenario("Alice", "Bob", "Carol")
        .apply {
            userFor("bob", Role.MEMBER)
            userFor("carol", Role.VIEWER)
        }
        .event("gig", "Alice", "Bob")
        .apply(AddHolding("gig", "h1", "alice", pln("100"), IncomeSource.HAT))

    private fun settling(memberId: String): Settling {
        val view = BandView(scenario.band)
        val member = scenario.band.member(memberId)!!
        return Settling(view, member, Capabilities(scenario.band, member.userId!!), DATE)
    }

    @Test
    fun aMemberSeesTheirOwnTransfersAndNothingForLeads() {
        val bob = settling("bob")
        assertEquals(listOf("gig"), bob.mine.map { it.event.id })
        assertEquals(listOf(SuggestedTransfer("alice", "bob", pln("50"))), bob.mine.single().suggestions)
        assertEquals(emptyList(), bob.forLeads)
        assertFalse(bob.isEmpty)
    }

    @Test
    fun aLeadSeesEveryOpenEventToo() {
        val alice = settling("alice")
        assertEquals(listOf("gig"), alice.mine.map { it.event.id }, "she pays")
        assertEquals(listOf("gig"), alice.forLeads.map { it.event.id })
        assertEquals(emptyList(), alice.holding, "listed under her own transfers, not again as holding")
        assertEquals(pln("50"), alice.held(alice.mine.single().settlement), "of the 100 she holds, 50 is hers to keep")
    }

    @Test
    fun aViewerSeesNothing() {
        val carol = settling("carol")
        assertTrue(carol.isEmpty)
    }

    @Test
    fun theFundsHolderKeepsWhatTheyWouldHandToTheFund() {
        val withFund = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
            .apply(SetTreasurer("alice"))
            .apply(SetFundAmount("gig", pln("30")))
            .apply(AddHolding("gig", "h1", "alice", pln("100"), IncomeSource.HAT))
        val settlement = withFund.settle("gig")
        assertTrue(Settling.keepsForFund(withFund.band, settlement, SuggestedTransfer("alice", BAND_FUND, pln("30"))))
        assertFalse(Settling.keepsForFund(withFund.band, settlement, SuggestedTransfer("bob", BAND_FUND, pln("30"))))
        assertFalse(Settling.keepsForFund(withFund.band, settlement, SuggestedTransfer("alice", "bob", pln("35"))))
    }

    @Test
    fun handingOverGoesFromWhoHoldsToWhoMayReceive() {
        val view = BandView(scenario.band)
        val gig = scenario.band.event("gig")!!
        val alice = HandOver(view, gig, view.settlement(gig), Capabilities(scenario.band, "user-alice"))
        assertEquals(listOf("alice"), alice.senders, "only Alice holds money")
        assertEquals(pln("100"), alice.amount("alice"))
        assertEquals(Money.ZERO, alice.amount("bob"))
        assertEquals(listOf("bob"), alice.recipients("alice"), "no fund without a treasurer, never herself")
        val bob = HandOver(view, gig, view.settlement(gig), Capabilities(scenario.band, "user-bob"))
        assertEquals(listOf("alice"), bob.senders, "a member records the transfers they are a side of: Alice handing to them")
        assertEquals(listOf("bob"), bob.recipients("alice"))
    }
}
