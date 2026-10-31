package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.MemberDeletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deleting a member added by mistake (ITERATION_7 §3.1): only who left no trace in the band. */
class MemberDeletionTest {
    private fun band() = Scenario("Alice", "Bob", "Carol", "Dan", "Erin")

    @Test
    fun aMemberWhoDidNothingIsDeleted() {
        val scenario = band().event("gig", "Alice", "Bob")
        val alice = scenario.userFor("alice", Role.OWNER)
        assertTrue(MemberDeletion.possible(scenario.band, "carol"))
        scenario.apply(DeleteMember("carol"), alice)
        assertNull(scenario.band.member("carol"))
    }

    @Test
    fun anythingReferringToTheMemberKeepsThem() {
        val scenario = band().event("gig", "Alice", "Bob")
        scenario.apply(AddCost("c1", "carol", pln("50"), DATE))
        scenario.apply(SetTreasurer("dan"))
        // Settling together is between people in the app.
        scenario.userFor("alice", Role.OWNER)
        scenario.userFor("erin", Role.MEMBER)
        scenario.apply(InviteToSettleTogether("erin", "alice"))
        assertFalse(MemberDeletion.possible(scenario.band, "bob"), "took part")
        assertFalse(MemberDeletion.possible(scenario.band, "carol"), "has a cost")
        assertFalse(MemberDeletion.possible(scenario.band, "dan"), "holds the band fund")
        assertFalse(MemberDeletion.possible(scenario.band, "erin"), "asked to settle together")
        assertFalse(MemberDeletion.possible(scenario.band, "alice"), "the owner, and asked to settle together")
        assertEquals(RejectionReason.MEMBER_HAS_HISTORY, scenario.rejected(DeleteMember("bob")).reason)
        assertEquals(RejectionReason.MEMBER_HAS_HISTORY, scenario.rejected(DeleteMember("carol")).reason)
    }

    @Test
    fun aLeaderWhoAddedSomeoneWithoutPlayingStays() {
        val scenario = band()
        val bob = scenario.userFor("bob", Role.LEAD)
        scenario.apply(CreateEvent("gig", "gig", DATE, participants = listOf(Participant("alice"))), bob)
        scenario.apply(UnlinkUser("bob"))
        assertFalse(MemberDeletion.possible(scenario.band, "bob"), "named as who added Alice")
    }

    @Test
    fun aLinkedMemberIsUnlinkedFirst() {
        val scenario = band()
        val alice = scenario.userFor("alice", Role.OWNER)
        scenario.userFor("carol", Role.MEMBER)
        assertEquals(RejectionReason.MEMBER_LINKED, scenario.rejected(DeleteMember("carol"), alice).reason)
        scenario.apply(UnlinkUser("carol"), alice).apply(DeleteMember("carol"), alice)
        assertNull(scenario.band.member("carol"))
    }

    @Test
    fun aLeaderWhoRecordedMoneyWithoutPlayingStays() {
        val scenario = band().event("gig", "Alice", "Carol")
        val bob = scenario.userFor("bob", Role.LEAD)
        scenario.apply(AddHolding("gig", "h1", "carol", pln("100"), IncomeSource.HAT), bob)
        scenario.apply(UnlinkUser("bob"))
        assertFalse(MemberDeletion.possible(scenario.band, "bob"), "named as who recorded Carol's money")
    }

    @Test
    fun undoingADeletionWhoseNameWasTakenSinceIsRefused() {
        val scenario = band()
        val before = scenario.band
        scenario.apply(DeleteMember("carol"))
        val undo = Undo("deleted", DeleteMember("carol"), BandChange.between(before, scenario.band))
        scenario.apply(AddMember("carol2", "Carol"))
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(undo).reason, "two members named Carol")
        scenario.apply(RenameMember("carol2", "Caroline"))
        scenario.apply(undo)
        assertEquals("Carol", scenario.band.member("carol")?.name)
    }

    @Test
    fun whoPaidIntoTheFundStays() {
        val scenario = band().apply(SetTreasurer("alice"))
        scenario.apply(AddFundEntry("fee", DATE, "Membership fee", pln("50"), paidBy = "carol"))
        assertFalse(MemberDeletion.possible(scenario.band, "carol"), "paid into the fund (ITERATION_7 §5.1)")
    }
}
