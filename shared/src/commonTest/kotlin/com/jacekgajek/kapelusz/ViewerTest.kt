package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import kotlin.test.Test
import kotlin.test.assertEquals

/** Viewers see everything but only watch: they take no part in events. */
class ViewerTest {

    @Test
    fun viewersAreNotAddedToEvents() {
        val scenario = Scenario("Alice", "Vera").event("gig", "Alice")
        scenario.userFor("vera", Role.VIEWER)
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(AddParticipant("gig", "vera")).reason)
        assertEquals(
            RejectionReason.VIEWER_NOT_PARTICIPANT,
            scenario.rejected(CreateEvent("new", "new", DATE, participants = listOf(Participant("alice"), Participant("vera")))).reason,
        )
    }

    /** V5-M1 B2: a viewer records no transfers, so they can't be left playing with money, or holding the band fund. */
    @Test
    fun aViewerCantBeLeftHoldingMoneyOrTheFund() {
        val scenario = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob").apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(SetMemberRole("bob", Role.VIEWER)).reason)
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(LinkUser("bob", "user-bob", Role.VIEWER)).reason)
        scenario.apply(SettleSuggestions("gig")).apply(LinkUser("bob", "user-bob", Role.VIEWER))
        assertEquals(Role.VIEWER, scenario.band.member("bob")!!.role, "settled: fine")
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(SetTreasurer("bob")).reason)
        scenario.apply(SetTreasurer("carol")).apply(LinkUser("carol", "user-carol", Role.MEMBER))
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(SetMemberRole("carol", Role.VIEWER)).reason, "the fund holder")
    }

    /** V5-M1 B3a: ownership never goes to an archived admin. */
    @Test
    fun noArchivedOwner() {
        val scenario = Scenario("Alice", "Bob").apply(LinkUser("bob", "user-bob", Role.ADMIN)).apply(SetMemberArchived("bob", true))
        assertEquals(RejectionReason.ARCHIVED_MEMBER, scenario.rejected(TransferOwnership("bob")).reason)
    }

    /** V5-M1 B3c: a settle op whose id was seen before can't make two transfers with one id. */
    @Test
    fun aRepeatedSettleOpIdIsRefused() {
        val band = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol").apply(AddHolding("gig", "h", "alice", pln("300"), DOOR)).band
        val first = (Engine.apply(band, Actor.System, SettleSuggestions("gig", "bob"), "same", 1) as ApplyResult.Applied).band
        val again = Engine.apply(first, Actor.System, SettleSuggestions("gig", "carol"), "same", 2)
        assertEquals(RejectionReason.DUPLICATE_ID, (again as ApplyResult.Rejected).rejection.reason)
    }
}
