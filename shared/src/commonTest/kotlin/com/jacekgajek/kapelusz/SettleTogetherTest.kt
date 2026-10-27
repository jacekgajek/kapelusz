package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.Artists
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
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.isUndoable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ITERATION_5 §7: two members settling together; artists too (ITERATION_6 §11). */
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
    fun artistsSettleTogetherViewersDoNot() {
        // ITERATION_6 §11: artists invite and answer too.
        val scenario = band()
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        scenario.apply(InviteToSettleTogether("carol", "bob"), scenario.userFor("carol", Role.ARTIST))
        scenario.apply(AcceptSettleTogether("carol", "bob"), scenario.userFor("bob", Role.MEMBER))
        assertEquals("bob", scenario.partner("carol"))

        val viewers = band()
        viewers.apply(SetMemberRole("carol", Role.VIEWER))
        assertEquals(RejectionReason.ROLE_REQUIRED, viewers.rejected(InviteToSettleTogether("carol", "bob"), viewers.userFor("carol", Role.VIEWER)).reason)
        assertEquals(RejectionReason.CANNOT_SETTLE_TOGETHER, viewers.rejected(InviteToSettleTogether("bob", "carol"), viewers.userFor("bob", Role.MEMBER)).reason)
    }

    @Test
    fun aPartnerKeepsAnArtistsShare() {
        // Bob collected 300; Alice, Bob and Carol (an artist, paired with Bob) get 100 each.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        scenario.apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
        val settlement = scenario.settle("gig")
        assertEquals(listOf(SuggestedTransfer("bob", "alice", pln("100"))), settlement.suggestions, "Bob keeps Carol's 100")
        assertEquals(pln("100"), SettleTogether.keptByPartner(settlement, "carol"))
        assertEquals(pln("0"), SettleTogether.keptByPartner(settlement, "bob"))
        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        assertEquals(pln("100"), SettleTogether.keptByPartner(scenario.settle("gig"), "carol"))
    }

    @Test
    fun twoArtistsArePaidAsOne() {
        // Alice collected 300; Bob and Carol, both artists and paired, are owed 100 each.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
        scenario.apply(SetMemberRole("bob", Role.ARTIST))
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        assertEquals("carol", scenario.partner("bob"), "the pair stays when both become artists")
        scenario.apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
        val suggestion = scenario.settle("gig").suggestions.single()
        assertEquals(pln("200"), suggestion.amount, "one transfer for the pair")
        scenario.apply(RecordTransfer("gig", "t", suggestion.from, suggestion.to, suggestion.amount, together = true))
        // Stored between real people, each artist up to what they are owed.
        assertEquals(listOf("bob" to pln("100"), "carol" to pln("100")), scenario.band.event("gig")!!.transfers.map { it.to to it.amount }.sortedBy { it.first })
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
    }

    /** Both artists of a pair paid, then a refund accepted makes both owe a little back (2026-10-27). */
    @Test
    fun twoOverpaidArtistsHandBackTheirOwnShares() {
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
        scenario.apply(SetMemberRole("bob", Role.ARTIST))
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        scenario.apply(AddHolding("gig", "h", "alice", pln("400"), DOOR))
        scenario.apply(RecordTransfer("gig", "t", "alice", "bob", pln("200"), together = true))
        scenario.apply(AddCost("daves", "dave", pln("40"), DATE, incurredForEventId = "gig"))
        scenario.apply(SetRefund("gig", "daves", pln("40")))

        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        val back = scenario.band.event("gig")!!.transfers.filter { it.from == "bob" || it.from == "carol" }
        assertEquals(listOf("bob" to pln("10"), "carol" to pln("10")), back.map { it.from to it.amount }.sortedBy { it.first }, "each their own")
    }

    @Test
    fun anArtistInAPairSeesNoTransferTheirPartnerReceives() {
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        scenario.apply(AddHolding("gig", "h", "alice", pln("400"), DOOR))
        // Alice pays the pair, recorded to Bob (ITERATION_5 §7.3): Carol's payout says Bob keeps hers; Alice → Dave isn't hers.
        val own = Artists.ownTransfers(scenario.band, scenario.settle("gig"), "carol")
        assertEquals(emptyList(), own.map { Triple(it.from, it.to, it.amount) })
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
    fun rolesFromArtistUpKeepThePair() {
        val scenario = band().pair()
        scenario.apply(SetMemberRole("bob", Role.LEAD))
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        assertEquals("carol", scenario.partner("bob"))
        assertEquals("bob", scenario.partner("carol"))
        scenario.apply(SetMemberRole("carol", Role.MEMBER))
        assertEquals("bob", scenario.partner("carol"))
    }

    @Test
    fun anInvitationStaysWithTheInviteeBecomingAnArtist() {
        val scenario = band()
        scenario.apply(InviteToSettleTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
        scenario.apply(SetMemberRole("carol", Role.ARTIST))
        assertEquals("carol", scenario.band.member("bob")!!.invitedToSettle)
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

    @Test
    fun settleAllSplitsEachTransferAgainstWhatTheOnesBeforeLeft() {
        // Carol (weight 200) is owed 200, Bob 100; Alice and Dave collected 250 each.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
        scenario.apply(SetWeight("gig", "carol", 200))
        scenario.apply(AddHolding("gig", "h1", "alice", pln("250"), DOOR))
        scenario.apply(AddHolding("gig", "h2", "dave", pln("250"), DOOR))
        scenario.apply(SettleSuggestions("gig"))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        // Alice's 150 goes to Carol (owed 200), Dave's to Bob, owed more by then.
        assertEquals(listOf("carol", "bob"), scenario.band.event("gig")!!.transfers.map { it.to })
    }

    @Test
    fun settleAllNeverTakesMoreFromAPartnerThanTheyHold() {
        // Bob holds 300, Carol 200; Alice and Dave are owed 250 each.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
        scenario.apply(AddHolding("gig", "h1", "bob", pln("300"), DOOR))
        scenario.apply(AddHolding("gig", "h2", "carol", pln("200"), DOOR))
        scenario.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "alice", pln("250")))
        scenario.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "dave", pln("250")))
        scenario.apply(SettleSuggestions("gig"))
        val settlement = scenario.settle("gig")
        assertEquals(EventStatus.SETTLED, settlement.status)
        assertEquals(pln("0"), settlement.line("bob")!!.balance)
        assertEquals(pln("0"), settlement.line("carol")!!.balance)
    }

    @Test
    fun endingAPairKeepsTheEventsItSettledSettled() {
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave").event("open", "Alice", "Bob", "Carol")
        scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
        scenario.apply(AddHolding("open", "h", "bob", pln("300"), DOOR))
        scenario.apply(SettleSuggestions("gig"))
        assertEquals(1, SettleTogether.openEventsTogether(scenario.band, "bob", "carol"))
        scenario.apply(StopSettlingTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
        assertEquals(EventStatus.SETTLED, scenario.settle("gig").status)
        assertEquals(listOf(listOf("bob", "carol")), scenario.band.event("gig")!!.settledTogether)
        assertEquals(listOf(SuggestedTransfer("bob", "alice", pln("100")), SuggestedTransfer("bob", "carol", pln("100"))), scenario.settle("open").suggestions)
        // Also when the pair ends by itself.
        val archived = band().pair().event("gig", "Alice", "Bob", "Carol")
        archived.apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
        archived.apply(SettleSuggestions("gig"))
        archived.apply(SetMemberArchived("carol", true))
        assertEquals(EventStatus.SETTLED, archived.settle("gig").status)
    }

    @Test
    fun aPartnerActsOnlyWhereBothPlay() {
        val scenario = band().pair().event("gig", "Alice", "Bob", "Dave")
        scenario.apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
        val carol = scenario.userFor("carol", Role.MEMBER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SettleSuggestions("gig", "bob"), carol).reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(RecordTransfer("gig", "x", "bob", "dave", pln("50"), together = true), carol).reason)
    }

    @Test
    fun undoingAnEndKeepsAnInvitationSentSince() {
        val scenario = band()
        scenario.userFor("dave", Role.MEMBER)
        scenario.pair()
        val before = scenario.band
        scenario.apply(SetMemberRole("carol", Role.VIEWER))
        val change = BandChange.between(before, scenario.band)
        scenario.apply(InviteToSettleTogether("bob", "dave"), scenario.userFor("bob", Role.MEMBER))
        // Bringing the pair back would leave Bob both paired and inviting: refused rather than dropping the invitation.
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(Undo("op-role", SetMemberRole("carol", Role.VIEWER), change)).reason)
        assertEquals("dave", scenario.band.member("bob")!!.invitedToSettle)
    }

    @Test
    fun aStoredPairWithAViewerNoLongerCounts() {
        // No change has tidied it yet.
        val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
        val stale = scenario.band.copy(members = scenario.band.members.map { if (it.id == "carol") it.copy(role = Role.VIEWER) else it })
        assertTrue(com.jacekgajek.kapelusz.domain.SettlementCalculator.settle(stale, stale.event("gig")!!).partners.isEmpty())
    }
}
