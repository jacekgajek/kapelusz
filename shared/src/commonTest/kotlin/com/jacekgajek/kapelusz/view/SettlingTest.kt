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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** The Settle tab's lists, as [Settling] computes them for each role. */
class SettlingTest :
    ExpectSpec({
        // Alice (owner) collected 100 at a gig with Bob (member) and Carol (viewer, not playing): Alice hands 50 to Bob.
        // Scenario is mutable, so each test builds its own.
        fun scenario() = Scenario("Alice", "Bob", "Carol")
            .apply {
                userFor("bob", Role.MEMBER)
                userFor("carol", Role.VIEWER)
            }
            .event("gig", "Alice", "Bob")
            .apply(AddHolding("gig", "h1", "alice", pln("100"), IncomeSource.HAT))

        fun settling(scenario: Scenario, memberId: String): Settling {
            val view = BandView(scenario.band)
            val member = scenario.band.member(memberId)!!
            return Settling(view, member, Capabilities(scenario.band, member.userId!!), DATE)
        }

        expect("a member sees their own transfers and nothing for leads") {
            val bob = settling(scenario(), "bob")
            bob.mine.map { it.event.id } shouldBe listOf("gig")
            bob.mine.single().suggestions shouldBe listOf(SuggestedTransfer("alice", "bob", pln("50")))
            bob.forLeads shouldBe emptyList()
            bob.isEmpty shouldBe false
        }

        expect("a lead sees every open event too") {
            val alice = settling(scenario(), "alice")
            withClue("she pays") { alice.mine.map { it.event.id } shouldBe listOf("gig") }
            alice.forLeads.map { it.event.id } shouldBe listOf("gig")
            withClue("listed under her own transfers, not again as holding") { alice.holding shouldBe emptyList() }
            withClue("of the 100 she holds, 50 is hers to keep") { alice.held(alice.mine.single().settlement) shouldBe pln("50") }
        }

        expect("a viewer sees nothing") {
            val carol = settling(scenario(), "carol")
            carol.isEmpty shouldBe true
        }

        expect("the fund's holder keeps what they would hand to the fund") {
            val withFund = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
                .apply(SetTreasurer("alice"))
                .apply(SetFundAmount("gig", pln("30")))
                .apply(AddHolding("gig", "h1", "alice", pln("100"), IncomeSource.HAT))
            val settlement = withFund.settle("gig")
            Settling.keepsForFund(withFund.band, settlement, SuggestedTransfer("alice", BAND_FUND, pln("30"))) shouldBe true
            Settling.keepsForFund(withFund.band, settlement, SuggestedTransfer("bob", BAND_FUND, pln("30"))) shouldBe false
            Settling.keepsForFund(withFund.band, settlement, SuggestedTransfer("alice", "bob", pln("35"))) shouldBe false
        }

        expect("handing over goes from who holds to who may receive") {
            val scenario = scenario()
            val view = BandView(scenario.band)
            val gig = scenario.band.event("gig")!!
            val alice = HandOver(view, gig, view.settlement(gig), Capabilities(scenario.band, "user-alice"))
            withClue("only Alice holds money") { alice.senders shouldBe listOf("alice") }
            alice.amount("alice") shouldBe pln("100")
            alice.amount("bob") shouldBe Money.ZERO
            withClue("no fund without a treasurer, never herself") { alice.recipients("alice") shouldBe listOf("bob") }
            val bob = HandOver(view, gig, view.settlement(gig), Capabilities(scenario.band, "user-bob"))
            withClue("a member records the transfers they are a side of: Alice handing to them") {
                bob.senders shouldBe listOf("alice")
            }
            bob.recipients("alice") shouldBe listOf("bob")
        }
    })
