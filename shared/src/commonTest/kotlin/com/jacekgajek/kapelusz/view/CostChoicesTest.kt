package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.rules.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a cost's form offers ([CostChoices]) and what an event's Refunds section lists as waiting ([Refunds]). */
class CostChoicesTest {
    // Alice (owner), Bob (member), Carol (artist), Dave (archived); a gig with Alice and Bob, a past one archived with Carol.
    private val scenario = Scenario("Alice", "Bob", "Carol", "Dave")
        .apply {
            userFor("bob", Role.MEMBER)
            userFor("carol", Role.ARTIST)
        }
        .apply(SetMemberArchived("dave", true))
        .event("gig", "Alice", "Bob")
        .event("old", "Carol")
        .apply(SetEventArchived("old", true))

    private fun choices(userId: String) = CostChoices(BandView(scenario.band), Capabilities(scenario.band, userId))

    @Test
    fun aLeadMayPayForAnyActiveMemberAMemberOnlyForThemselves() {
        assertEquals(listOf("alice", "bob", "carol"), choices("user-alice").payers(null).map { it.id }, "alphabetical, Dave is archived")
        assertEquals(listOf("bob"), choices("user-bob").payers(null).map { it.id })
        assertEquals(listOf("carol"), choices("user-carol").payers(null).map { it.id }, "artists look after their own costs")
    }

    @Test
    fun forAnEventOnlyItsParticipantsCanHavePaid() {
        val gig = scenario.band.event("gig")!!
        assertEquals(listOf("alice", "bob"), choices("user-alice").payers(gig).map { it.id })
    }

    @Test
    fun eventsAreThePayersOwnNotArchivedUnlessTheCostIsAlreadyThere() {
        val lead = choices("user-alice")
        assertEquals(listOf(null, "gig"), lead.events(existing = null, paidBy = "bob", currentEventId = null))
        assertEquals(listOf(null), lead.events(existing = null, paidBy = "carol", currentEventId = null), "her only event is archived")
        val opened = lead.events(existing = null, paidBy = "carol", currentEventId = "old")
        assertEquals(listOf(null, "old"), opened, "the form opened for it keeps it")
        assertEquals(listOf(null), lead.events(existing = null, paidBy = null, currentEventId = null))
    }

    @Test
    fun aCostsOwnEventStaysListedWhileItIsStillTheOwners() {
        val withCost = scenario.apply(AddCost("c1", "carol", pln("20"), DATE))
        val cost = withCost.band.cost("c1")!!.copy(eventId = "old")
        val lead = CostChoices(BandView(withCost.band), Capabilities(withCost.band, "user-alice"))
        assertEquals(listOf(null, "old"), lead.events(existing = cost, paidBy = "carol", currentEventId = null))
        val bobs = lead.events(existing = cost, paidBy = "bob", currentEventId = null)
        assertEquals(listOf(null, "gig"), bobs, "another payer: not their event")
    }

    @Test
    fun waitingCostsAreTheLeadsToIncludeAndTheOwnersToSee() {
        // Bob's own cost waits; a lead's for an open event would be included at once.
        val withCosts = scenario
            .apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), Actor.User("user-bob"))
            .apply(AddCost("alices", "alice", pln("10"), DATE), Actor.User("user-alice"))
        val view = BandView(withCosts.band)
        val gig = withCosts.band.event("gig")!!
        val pending = Refunds.pending(view, gig, canEdit = true).map { it.id }
        assertEquals(listOf("bobs", "alices"), pending, "oldest first, both participants' costs")
        assertEquals(emptyList(), Refunds.pending(view, gig, canEdit = false))
        assertEquals(listOf("bobs"), Refunds.mine(view, gig, withCosts.band.member("bob"), canEdit = false).map { it.id })
        assertEquals(emptyList(), Refunds.mine(view, gig, withCosts.band.member("alice"), canEdit = true), "a lead sees them under pending")
    }
}
