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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** What a cost's form offers ([CostChoices]) and what an event's Refunds section lists as waiting ([Refunds]). */
class CostChoicesTest :
    ExpectSpec({
        // Alice (owner), Bob (member), Carol (artist), Dave (archived); a gig with Alice and Bob, a past one archived with Carol.
        // Scenario is mutable, so each test builds its own.
        fun scenario() = Scenario("Alice", "Bob", "Carol", "Dave")
            .apply {
                userFor("bob", Role.MEMBER)
                userFor("carol", Role.ARTIST)
            }
            .apply(SetMemberArchived("dave", true))
            .event("gig", "Alice", "Bob")
            .event("old", "Carol")
            .apply(SetEventArchived("old", true))

        fun choices(scenario: Scenario, userId: String) = CostChoices(BandView(scenario.band), Capabilities(scenario.band, userId))

        expect("a lead may pay for any active member, a member only for themselves") {
            val scenario = scenario()
            withClue("alphabetical, Dave is archived") {
                choices(scenario, "user-alice").payers(null).map { it.id } shouldBe listOf("alice", "bob", "carol")
            }
            choices(scenario, "user-bob").payers(null).map { it.id } shouldBe listOf("bob")
            withClue("artists look after their own costs") {
                choices(scenario, "user-carol").payers(null).map { it.id } shouldBe listOf("carol")
            }
        }

        expect("for an event only its participants can have paid") {
            val scenario = scenario()
            val gig = scenario.band.event("gig")!!
            choices(scenario, "user-alice").payers(gig).map { it.id } shouldBe listOf("alice", "bob")
        }

        expect("events are the payer's own, not archived, unless the cost is already there") {
            val lead = choices(scenario(), "user-alice")
            lead.events(existing = null, paidBy = "bob", currentEventId = null) shouldBe listOf(null, "gig")
            withClue("her only event is archived") {
                lead.events(existing = null, paidBy = "carol", currentEventId = null) shouldBe listOf(null)
            }
            val opened = lead.events(existing = null, paidBy = "carol", currentEventId = "old")
            withClue("the form opened for it keeps it") { opened shouldBe listOf(null, "old") }
            lead.events(existing = null, paidBy = null, currentEventId = null) shouldBe listOf(null)
        }

        expect("a cost's own event stays listed while it is still the owner's") {
            val withCost = scenario().apply(AddCost("c1", "carol", pln("20"), DATE))
            val cost = withCost.band.cost("c1")!!.copy(eventId = "old")
            val lead = CostChoices(BandView(withCost.band), Capabilities(withCost.band, "user-alice"))
            lead.events(existing = cost, paidBy = "carol", currentEventId = null) shouldBe listOf(null, "old")
            val bobs = lead.events(existing = cost, paidBy = "bob", currentEventId = null)
            withClue("another payer: not their event") { bobs shouldBe listOf(null, "gig") }
        }

        expect("waiting costs are the lead's to include and the owner's to see") {
            // Bob's own cost waits; a lead's for an open event would be included at once.
            val withCosts = scenario()
                .apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), Actor.User("user-bob"))
                .apply(AddCost("alices", "alice", pln("10"), DATE), Actor.User("user-alice"))
            val view = BandView(withCosts.band)
            val gig = withCosts.band.event("gig")!!
            val pending = Refunds.pending(view, gig, canEdit = true).map { it.id }
            withClue("oldest first, both participants' costs") { pending shouldBe listOf("bobs", "alices") }
            Refunds.pending(view, gig, canEdit = false) shouldBe emptyList()
            Refunds.mine(view, gig, withCosts.band.member("bob"), canEdit = false).map { it.id } shouldBe listOf("bobs")
            withClue("a lead sees them under pending") {
                Refunds.mine(view, gig, withCosts.band.member("alice"), canEdit = true) shouldBe emptyList()
            }
        }
    })
