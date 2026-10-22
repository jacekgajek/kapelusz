package com.jacekgajek.splitart

import com.jacekgajek.splitart.auth.Actor
import com.jacekgajek.splitart.auth.Permissions
import com.jacekgajek.splitart.model.BAND_FUND
import com.jacekgajek.splitart.model.IncomeSource.DOOR
import com.jacekgajek.splitart.model.Role
import com.jacekgajek.splitart.ops.AddCost
import com.jacekgajek.splitart.ops.AddHolding
import com.jacekgajek.splitart.ops.AddMember
import com.jacekgajek.splitart.ops.Change
import com.jacekgajek.splitart.ops.CreateEvent
import com.jacekgajek.splitart.ops.LinkUser
import com.jacekgajek.splitart.ops.Operation
import com.jacekgajek.splitart.ops.RecordTransfer
import com.jacekgajek.splitart.ops.RejectionCode
import com.jacekgajek.splitart.ops.RejectionReason
import com.jacekgajek.splitart.ops.SetMemberArchived
import com.jacekgajek.splitart.ops.SetMemberRole
import com.jacekgajek.splitart.ops.SetRefund
import com.jacekgajek.splitart.ops.SetTreasurer
import com.jacekgajek.splitart.ops.SettleSuggestions
import com.jacekgajek.splitart.ops.TransferOwnership
import com.jacekgajek.splitart.ops.UpdateCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsTest {

    private val scenario = Scenario("Owner", "Admin", "Lead", "Member", "Viewer", "Other")
        .event("gig", "Owner", "Lead", "Member", "Other")
        .apply(AddHolding("gig", "h-other", "other", "100".let(::pln), DOOR))

    private val owner = Actor.User("user-owner")
    private val admin = scenario.userFor("admin", Role.ADMIN)
    private val lead = scenario.userFor("lead", Role.LEAD)
    private val member = scenario.userFor("member", Role.MEMBER)
    private val viewer = scenario.userFor("viewer", Role.VIEWER)

    private fun allowed(actor: Actor, op: Operation) = Permissions.isAllowed(scenario.band, actor, op)

    @Test
    fun viewerIsReadOnly() {
        assertFalse(allowed(viewer, AddCost("c", "viewer", pln("1"), DATE)))
        assertFalse(allowed(viewer, RecordTransfer("gig", "t", "viewer", "member", pln("1"))))
    }

    @Test
    fun memberActsOnlyForThemselves() {
        assertTrue(allowed(member, AddCost("c", "member", pln("1"), DATE)))
        assertFalse(allowed(member, AddCost("c", "other", pln("1"), DATE)))
        assertTrue(allowed(member, AddHolding("gig", "h", "member", pln("1"), DOOR)))
        assertFalse(allowed(member, AddHolding("gig", "h", "other", pln("1"), DOOR)))
        assertTrue(allowed(member, RecordTransfer("gig", "t", "other", "member", pln("1"))))
        assertFalse(allowed(member, RecordTransfer("gig", "t", "other", "lead", pln("1"))))
        assertTrue(allowed(member, SettleSuggestions("gig", "member")))
        assertFalse(allowed(member, SettleSuggestions("gig")))
        assertFalse(allowed(member, CreateEvent("e2", "e2", DATE)))
        assertFalse(allowed(member, SetRefund("gig", "c", pln("1"))))
    }

    @Test
    fun memberHoldingRequiresParticipation() {
        val outsider = scenario.userFor("viewer", Role.VIEWER)
        scenario.apply(SetMemberRole("viewer", Role.MEMBER))
        assertFalse(allowed(outsider, AddHolding("gig", "h", "viewer", pln("1"), DOOR)))
    }

    @Test
    fun ownCostLocksOnceRefundedInEventWithTransfers() {
        scenario.apply(AddCost("c", "member", pln("10"), DATE, incurredForEventId = "gig"))
        val edit = UpdateCost("c", amount = Change(pln("12")))
        assertTrue(allowed(member, edit))

        scenario.apply(RecordTransfer("gig", "t", "other", "member", pln("10")))
        assertFalse(allowed(member, edit))
        assertTrue(allowed(lead, edit))
    }

    @Test
    fun leadRunsEventsButNotRoster() {
        assertTrue(allowed(lead, CreateEvent("e2", "e2", DATE)))
        assertTrue(allowed(lead, SettleSuggestions("gig")))
        assertTrue(allowed(lead, AddHolding("gig", "h", "other", pln("1"), DOOR)))
        assertFalse(allowed(lead, AddMember("x", "X")))
        assertFalse(allowed(lead, SetMemberRole("member", Role.LEAD)))
    }

    @Test
    fun adminManagesRosterButOnlyOwnerTransfersOwnership() {
        assertTrue(allowed(admin, AddMember("x", "X")))
        assertTrue(allowed(admin, SetMemberRole("member", Role.LEAD)))
        assertFalse(allowed(admin, TransferOwnership("admin")))
        assertTrue(allowed(owner, TransferOwnership("admin")))
    }

    @Test
    fun outsidersArchivedMembersAndLinkingAreForbidden() {
        val stranger = Permissions.check(scenario.band, Actor.User("stranger"), AddCost("c", "member", pln("1"), DATE))!!
        assertEquals(RejectionCode.FORBIDDEN, stranger.code)
        assertEquals(RejectionReason.NOT_A_MEMBER, stranger.reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, Permissions.check(scenario.band, member, CreateEvent("e2", "e2", DATE))!!.reason)
        assertFalse(allowed(owner, LinkUser("other", "someone", Role.MEMBER)))
        assertTrue(allowed(Actor.System, LinkUser("other", "someone", Role.MEMBER)))

        scenario.apply(SetMemberArchived("member", true))
        assertFalse(allowed(member, AddCost("c", "member", pln("1"), DATE)))
    }

    @Test
    fun treasurerActsForBandFund() {
        val toFund = RecordTransfer("gig", "t", "other", BAND_FUND, pln("1"))
        assertFalse(allowed(member, toFund))
        assertFalse(allowed(member, SettleSuggestions("gig", BAND_FUND)))

        scenario.apply(SetTreasurer("member"))
        assertTrue(allowed(member, toFund))
        assertTrue(allowed(member, RecordTransfer("gig", "t", BAND_FUND, "other", pln("1"))))
        assertTrue(allowed(member, SettleSuggestions("gig", BAND_FUND)))
        assertFalse(allowed(member, SettleSuggestions("gig")))
    }
}
