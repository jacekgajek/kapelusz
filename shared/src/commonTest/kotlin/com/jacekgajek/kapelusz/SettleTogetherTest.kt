package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.domain.SuggestedTransfer
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.isUndoable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ITERATION_5 §7: two members settling together. */
class SettleTogetherTest {

    /** Alice owns the band; Bob and Carol are members with accounts, Dave has none. */
    private fun band(): Scenario {
        val scenario = Scenario("Alice", "Bob", "Carol", "Dave")
        scenario.userFor("bob", Role.MEMBER)
        scenario.userFor("carol", Role.MEMBER)
        return scenario
    }

    private fun Scenario.pair(a: String = "bob", b: String = "carol"): Scenario {
        apply(InviteToSettleTogether(a, b), userFor(a, Role.MEMBER))
        return apply(AcceptSettleTogether(a, b), userFor(b, Role.MEMBER))
    }

    private fun Scenario.partner(id: String) = band.member(id)!!.settlesWith

    @Test
    fun anInvitationAcceptedPairsBoth() {
        val scenario = band()
        scenario.apply(InviteToSettleTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
        assertEquals("carol", scenario.band.member("bob")!!.invitedToSettle)
        assertEquals("bob", SettleTogether.inviterOf(scenario.band, "carol")?.id)
        scenario.apply(AcceptSettleTogether("bob", "carol"), scenario.userFor("carol", Role.MEMBER))
        assertEquals("carol", scenario.partner("bob"))
        assertEquals("bob", scenario.partner("carol"))
        assertNull(scenario.band.member("bob")!!.invitedToSettle)

        scenario.apply(StopSettlingTogether("carol", "bob"), scenario.userFor("carol", Role.MEMBER))
        assertNull(scenario.partner("bob"))
        assertNull(scenario.partner("carol"))
    }

    @Test
    fun declinedOrCancelledInvitationsLeaveNoPair() {
        val scenario = band()
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
        scenario.apply(DeclineSettleTogether("bob", "carol"), scenario.userFor("carol", Role.MEMBER))
        assertNull(scenario.band.member("bob")!!.invitedToSettle)
        scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
        scenario.apply(CancelSettleTogether("bob", "carol"), bob)
        assertNull(scenario.band.member("bob")!!.invitedToSettle)
        assertNull(scenario.partner("carol"))
    }

    @Test
    fun onlyFreeMembersWithAccountsCanBeInvited() {
        val scenario = band()
        val bob = scenario.userFor("bob", Role.MEMBER)
        assertEquals(RejectionReason.CANNOT_SETTLE_TOGETHER, scenario.rejected(InviteToSettleTogether("bob", "dave"), bob).reason, "no account")
        assertEquals(RejectionReason.CANNOT_SETTLE_TOGETHER, scenario.rejected(InviteToSettleTogether("bob", "bob"), bob).reason)
        scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
        val alice = scenario.userFor("alice", Role.OWNER)
        assertEquals(RejectionReason.CANNOT_SETTLE_TOGETHER, scenario.rejected(InviteToSettleTogether("alice", "carol"), alice).reason, "invited already")
        assertEquals(RejectionReason.CANNOT_SETTLE_TOGETHER, scenario.rejected(InviteToSettleTogether("alice", "bob"), alice).reason, "inviting already")
        assertEquals(RejectionReason.CANNOT_SETTLE_TOGETHER, scenario.rejected(InviteToSettleTogether("bob", "alice"), bob).reason, "one at a time")
    }

    @Test
    fun everyoneDecidesOnlyForThemselves() {
        val scenario = band()
        val alice = scenario.userFor("alice", Role.OWNER)
        val bob = scenario.userFor("bob", Role.MEMBER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(InviteToSettleTogether("bob", "carol"), alice).reason, "not even the owner for others")
        scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(AcceptSettleTogether("bob", "carol"), bob).reason, "the invited one answers")
        scenario.apply(AcceptSettleTogether("bob", "carol"), scenario.userFor("carol", Role.MEMBER))
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(StopSettlingTogether("bob", "carol"), alice).reason)
    }

    @Test
    fun artistsAcceptButNeitherInviteNorStop() {
        val scenario = band()
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        val carol = scenario.userFor("carol", Role.ARTIST)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(InviteToSettleTogether("carol", "bob"), carol).reason)
        scenario.pair()
        assertEquals("bob", scenario.partner("carol"))
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(StopSettlingTogether("carol", "bob"), carol).reason)
        scenario.apply(StopSettlingTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
    }

    @Test
    fun archivingUnlinkingOrViewersEndThePair() {
        for (end in listOf(SetMemberArchived("carol", true), UnlinkUser("carol"), SetMemberRole("carol", Role.VIEWER))) {
            val scenario = band().pair()
            scenario.apply(end)
            assertNull(scenario.partner("bob"), "$end")
            assertNull(scenario.partner("carol"), "$end")
        }
    }

    @Test
    fun anInvitationEndsWithTheInviteeLeaving() {
        val scenario = band()
        scenario.apply(InviteToSettleTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
        scenario.apply(SetMemberArchived("carol", true))
        assertNull(scenario.band.member("bob")!!.invitedToSettle)
    }

    @Test
    fun rolesKeepThePairUnlessBothAreArtists() {
        val scenario = band().pair()
        scenario.apply(SetMemberRole("bob", Role.LEAD))
        scenario.apply(SetMemberRole("bob", Role.ADMIN))
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        assertEquals("carol", scenario.partner("bob"))
        scenario.apply(SetMemberRole("bob", Role.ARTIST))
        assertNull(scenario.partner("bob"))
        assertNull(scenario.partner("carol"))
    }

    @Test
    fun undoingAnArchiveBringsThePairBack() {
        val scenario = band().pair()
        val before = scenario.band
        scenario.apply(SetMemberArchived("carol", true))
        val change = BandChange.between(before, scenario.band)
        scenario.apply(Undo("op-archive", SetMemberArchived("carol", true), change))
        assertEquals("carol", scenario.partner("bob"))
        assertEquals("bob", scenario.partner("carol"))
    }

    @Test
    fun undoingAnOlderChangeKeepsAPairFormedSince() {
        val scenario = band()
        val before = scenario.band
        scenario.apply(com.jacekgajek.kapelusz.ops.RenameMember("carol", "Caroline"))
        val change = BandChange.between(before, scenario.band)
        scenario.pair()
        scenario.apply(Undo("op-rename", com.jacekgajek.kapelusz.ops.RenameMember("carol", "Caroline"), change))
        assertEquals("Carol", scenario.band.member("carol")!!.name)
        assertEquals("bob", scenario.partner("carol"))
    }

    @Test
    fun settlingTogetherCannotBeUndone() {
        assertFalse(isUndoable(AcceptSettleTogether("bob", "carol")))
        assertFalse(isUndoable(StopSettlingTogether("bob", "carol")))
        assertFalse(isUndoable(InviteToSettleTogether("bob", "carol")))
    }

    @Test
    fun aPairIsOnePartyOnSettlement() {
        // Bob collected 400; four equal shares of 100.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
        scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
        val settlement = scenario.settle("gig")
        assertEquals(4, settlement.lines.size, "each still has their own numbers")
        assertEquals(listOf("alice", "bob", "dave"), settlement.parties.map { it.partyId })
        assertEquals("carol", settlement.party("carol")!!.partnerId)
        assertEquals(pln("200"), settlement.party("carol")!!.delta)
        assertEquals(
            listOf(SuggestedTransfer("bob", "alice", pln("100")), SuggestedTransfer("bob", "dave", pln("100"))),
            settlement.suggestions,
            "the pair keeps Carol's 100",
        )
        assertEquals(2, settlement.suggestionsFor("carol").size)

        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        assertEquals(pln("-100"), scenario.settle("gig").line("carol")!!.delta, "still owed on her own, but settled as a pair")
    }

    @Test
    fun withoutThePartnerAMemberSettlesAlone() {
        val scenario = band().pair().event("gig", "Alice", "Bob")
        scenario.apply(AddHolding("gig", "h", "bob", pln("200"), DOOR))
        val settlement = scenario.settle("gig")
        assertTrue(settlement.partners.isEmpty())
        assertEquals(listOf(SuggestedTransfer("bob", "alice", pln("100"))), settlement.suggestions)
    }

    @Test
    fun aPairPaysOutOfWhatEachHolds() {
        // Bob and Carol collected 100 each; Alice is owed it all as a fixed share.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
        scenario.apply(AddHolding("gig", "h1", "bob", pln("100"), DOOR))
        scenario.apply(AddHolding("gig", "h2", "carol", pln("100"), DOOR))
        scenario.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "alice", pln("150")))
        val settlement = scenario.settle("gig")
        assertEquals(listOf(SuggestedTransfer("bob", "alice", pln("150"))), settlement.suggestions)

        scenario.apply(RecordTransfer("gig", "t", "carol", "alice", pln("150"), together = true), scenario.userFor("carol", Role.MEMBER))
        val transfers = scenario.band.event("gig")!!.transfers.map { Triple(it.id, it.from, it.amount) }
        assertEquals(listOf(Triple("t", "bob", pln("100")), Triple("t-1", "carol", pln("50"))), transfers)
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun aPairReceivesAsOneButAnArtistNoMoreThanOwed() {
        // Alice collected 600; Carol (an artist) has twice the weight: Alice 150, Bob 150, Carol 300.
        val scenario = band()
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        scenario.pair().event("gig", "Alice", "Bob", "Carol")
        scenario.apply(SetWeight("gig", "carol", 200))
        scenario.apply(AddHolding("gig", "h", "alice", pln("600"), DOOR))
        assertEquals(listOf(SuggestedTransfer("alice", "bob", pln("450"))), scenario.settle("gig").suggestions)

        scenario.apply(SettleSuggestions("gig"))
        val transfers = scenario.band.event("gig")!!.transfers.map { Triple(it.to, it.amount, it.id.count { c -> c == '-' }) }
        assertEquals(listOf(Triple("carol", pln("300"), 1), Triple("bob", pln("150"), 2)), transfers, "the one owed more first, capped")
    }

    @Test
    fun theTreasurersPairHandsTheFundsPartToTheTreasurer() {
        // Carol collected 400; 100 goes to the fund Bob holds, 100 to each of the three.
        val scenario = band()
        scenario.apply(SetTreasurer("bob"))
        scenario.pair().event("gig", "Alice", "Bob", "Carol")
        scenario.apply(AddHolding("gig", "h", "carol", pln("400"), DOOR))
        scenario.apply(SetFundAmount("gig", pln("100")))
        assertEquals(
            listOf(SuggestedTransfer("bob", BAND_FUND, pln("100")), SuggestedTransfer("bob", "alice", pln("100"))),
            scenario.settle("gig").suggestions,
        )
        scenario.apply(SettleSuggestions("gig"))
        val transfers = scenario.band.event("gig")!!.transfers.map { Triple(it.from, it.to, it.amount) }
        assertEquals(listOf(Triple("bob", BAND_FUND, pln("100")), Triple("carol", "alice", pln("100"))), transfers)
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    @Test
    fun eitherPartnerSettlesThePairsTransfers() {
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
        scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
        val carol = scenario.userFor("carol", Role.MEMBER)
        scenario.apply(SettleSuggestions("gig", "carol"), carol)
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        // Not as a plain transfer of Bob's: only a suggestion's, together.
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RecordTransfer("gig", "x", "bob", "dave", pln("1")), carol).reason)
    }
}
