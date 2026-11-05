package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.account
import com.jacekgajek.kapelusz.engine.isUndoable
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.SettleTogether
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import com.jacekgajek.kapelusz.view.BandView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

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

/** Two members settling together; artists too. */
class SettleTogetherTest :
    ExpectSpec({

        expect("an invitation accepted pairs both") {
            val scenario = band()
            scenario.apply(InviteToSettleTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
            scenario.band.member("bob")!!.invitedToSettle shouldBe "carol"
            SettleTogether.inviterOf(scenario.band, "carol")?.id shouldBe "bob"
            scenario.apply(AcceptSettleTogether("bob", "carol"), scenario.userFor("carol", Role.MEMBER))
            scenario.partner("bob") shouldBe "carol"
            scenario.partner("carol") shouldBe "bob"
            scenario.band.member("bob")!!.invitedToSettle.shouldBeNull()

            scenario.apply(StopSettlingTogether("carol", "bob"), scenario.userFor("carol", Role.MEMBER))
            scenario.partner("bob").shouldBeNull()
            scenario.partner("carol").shouldBeNull()
        }

        expect("declined or cancelled invitations leave no pair") {
            val scenario = band()
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
            scenario.apply(DeclineSettleTogether("bob", "carol"), scenario.userFor("carol", Role.MEMBER))
            scenario.band.member("bob")!!.invitedToSettle.shouldBeNull()
            scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
            scenario.apply(CancelSettleTogether("bob", "carol"), bob)
            scenario.band.member("bob")!!.invitedToSettle.shouldBeNull()
            scenario.partner("carol").shouldBeNull()
        }

        expect("only free members with accounts can be invited") {
            val scenario = band()
            val bob = scenario.userFor("bob", Role.MEMBER)
            withClue("no account") {
                scenario.rejected(InviteToSettleTogether("bob", "dave"), bob).reason shouldBe RejectionReason.CANNOT_SETTLE_TOGETHER
            }
            scenario.rejected(InviteToSettleTogether("bob", "bob"), bob).reason shouldBe RejectionReason.CANNOT_SETTLE_TOGETHER
            scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
            val alice = scenario.userFor("alice", Role.OWNER)
            withClue("invited already") {
                scenario.rejected(InviteToSettleTogether("alice", "carol"), alice).reason shouldBe RejectionReason.CANNOT_SETTLE_TOGETHER
            }
            withClue("inviting already") {
                scenario.rejected(InviteToSettleTogether("alice", "bob"), alice).reason shouldBe RejectionReason.CANNOT_SETTLE_TOGETHER
            }
            withClue("one at a time") {
                scenario.rejected(InviteToSettleTogether("bob", "alice"), bob).reason shouldBe RejectionReason.CANNOT_SETTLE_TOGETHER
            }
        }

        expect("everyone decides only for themselves") {
            val scenario = band()
            val alice = scenario.userFor("alice", Role.OWNER)
            val bob = scenario.userFor("bob", Role.MEMBER)
            withClue("not even the owner for others") {
                scenario.rejected(InviteToSettleTogether("bob", "carol"), alice).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            scenario.apply(InviteToSettleTogether("bob", "carol"), bob)
            withClue("the invited one answers") {
                scenario.rejected(AcceptSettleTogether("bob", "carol"), bob).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            scenario.apply(AcceptSettleTogether("bob", "carol"), scenario.userFor("carol", Role.MEMBER))
            scenario.rejected(StopSettlingTogether("bob", "carol"), alice).reason shouldBe RejectionReason.ROLE_REQUIRED
        }

        expect("artists settle together, viewers do not") {
            // Artists invite and answer too.
            val scenario = band()
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.apply(InviteToSettleTogether("carol", "bob"), scenario.userFor("carol", Role.ARTIST))
            scenario.apply(AcceptSettleTogether("carol", "bob"), scenario.userFor("bob", Role.MEMBER))
            scenario.partner("carol") shouldBe "bob"

            val viewers = band()
            viewers.apply(SetMemberRole("carol", Role.VIEWER))
            viewers.rejected(
                InviteToSettleTogether("carol", "bob"),
                viewers.userFor("carol", Role.VIEWER),
            ).reason shouldBe RejectionReason.ROLE_REQUIRED
            viewers.rejected(
                InviteToSettleTogether("bob", "carol"),
                viewers.userFor("bob", Role.MEMBER),
            ).reason shouldBe RejectionReason.CANNOT_SETTLE_TOGETHER
        }

        expect("a partner keeps an artist's share") {
            // Bob collected 300; Alice, Bob and Carol (an artist, paired with Bob) get 100 each.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
            val settlement = scenario.settle("gig")
            withClue("Bob keeps Carol's 100") {
                settlement.suggestions shouldBe listOf(SuggestedTransfer("bob", "alice", pln("100")))
            }
            SettleTogether.keptByPartner(settlement, "carol") shouldBe pln("100")
            SettleTogether.keptByPartner(settlement, "bob") shouldBe pln("0")
            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            SettleTogether.keptByPartner(scenario.settle("gig"), "carol") shouldBe pln("100")
        }

        expect("two artists are paid as one") {
            // Alice collected 300; Bob and Carol, both artists and paired, are owed 100 each.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
            scenario.apply(SetMemberRole("bob", Role.ARTIST))
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            withClue("the pair stays when both become artists") { scenario.partner("bob") shouldBe "carol" }
            scenario.apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
            val suggestion = scenario.settle("gig").suggestions.single()
            withClue("one transfer for the pair") { suggestion.amount shouldBe pln("200") }
            scenario.apply(RecordTransfer("gig", "t", suggestion.from, suggestion.to, suggestion.amount, together = true))
            // Stored between real people, each artist up to what they are owed.
            scenario.band.event("gig")!!.transfers.map { it.to to it.amount }.sortedBy { it.first } shouldBe
                listOf("bob" to pln("100"), "carol" to pln("100"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        // Both artists of a pair paid, then a refund accepted makes both owe a little back (2026-10-27).
        expect("two overpaid artists hand back their own shares") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(SetMemberRole("bob", Role.ARTIST))
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.apply(AddHolding("gig", "h", "alice", pln("400"), DOOR))
            scenario.apply(RecordTransfer("gig", "t", "alice", "bob", pln("200"), together = true))
            scenario.apply(AddCost("daves", "dave", pln("40"), DATE, incurredForEventId = "gig"))
            scenario.apply(SetRefund("gig", "daves", pln("40")))

            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            val back = scenario.band.event("gig")!!.transfers.filter { it.from == "bob" || it.from == "carol" }
            withClue("each their own") {
                back.map { it.from to it.amount }.sortedBy { it.first } shouldBe listOf("bob" to pln("10"), "carol" to pln("10"))
            }
        }

        // A pair's amount never exceeds what its artists were paid too much, so no leftover is left to place.
        expect("two overpaid artists in a pair split a surplus into their own deltas") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(SetMemberRole("bob", Role.ARTIST))
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.apply(AddHolding("gig", "h", "alice", pln("400"), DOOR))
            scenario.apply(RecordTransfer("gig", "t", "alice", "bob", pln("200"), together = true))
            scenario.apply(AddCost("daves", "dave", pln("40"), DATE, incurredForEventId = "gig"))
            scenario.apply(SetRefund("gig", "daves", pln("40")))
            val settlement = scenario.settle("gig")
            val amount = pln("20")

            val parts = SettleTogether.split(scenario.band, settlement, "bob", "dave", amount)
            parts.sumOf { it.amount.minor } shouldBe amount.minor
            parts.forEach { part -> part.amount.minor shouldBeLessThanOrEqualTo settlement.line(part.from)!!.delta.minor }
        }

        expect("an artist in a pair sees no transfer their partner receives") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.apply(AddHolding("gig", "h", "alice", pln("400"), DOOR))
            // Alice pays the pair, recorded to Bob: Carol's payout says Bob keeps hers; Alice → Dave isn't hers.
            val own = Artists.ownTransfers(scenario.band, scenario.settle("gig"), "carol")
            own.map { Triple(it.from, it.to, it.amount) }.shouldBeEmpty()
        }

        // An artist records only their own transfers as themselves: a pair's is split by what each holds.
        expect("an artist in a pair cannot move their partner's money") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            val carol = scenario.userFor("carol", Role.ARTIST)
            scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
            for (op in listOf(
                RecordTransfer("gig", "t1", "bob", "alice", pln("100"), together = true),
                RecordTransfer("gig", "t2", "carol", "alice", pln("100"), together = true),
                RecordTransfer("gig", "t3", "dave", "carol", pln("100"), together = true),
            )) {
                withClue("$op") { scenario.rejected(op, carol).reason shouldBe RejectionReason.ROLE_REQUIRED }
            }
        }

        expect("archiving, unlinking or viewers end the pair") {
            for (end in listOf(SetMemberArchived("carol", true), UnlinkUser("carol"), SetMemberRole("carol", Role.VIEWER))) {
                val scenario = band().pair()
                scenario.apply(end)
                withClue("$end") {
                    scenario.partner("bob").shouldBeNull()
                    scenario.partner("carol").shouldBeNull()
                }
            }
        }

        expect("an invitation ends with the invitee leaving") {
            val scenario = band()
            scenario.apply(InviteToSettleTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
            scenario.apply(SetMemberArchived("carol", true))
            scenario.band.member("bob")!!.invitedToSettle.shouldBeNull()
        }

        expect("roles from artist up keep the pair") {
            val scenario = band().pair()
            scenario.apply(SetMemberRole("bob", Role.LEAD))
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.partner("bob") shouldBe "carol"
            scenario.partner("carol") shouldBe "bob"
            scenario.apply(SetMemberRole("carol", Role.MEMBER))
            scenario.partner("carol") shouldBe "bob"
        }

        expect("an invitation stays with the invitee becoming an artist") {
            val scenario = band()
            scenario.apply(InviteToSettleTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
            scenario.apply(SetMemberRole("carol", Role.ARTIST))
            scenario.band.member("bob")!!.invitedToSettle shouldBe "carol"
        }

        expect("undoing an archive brings the pair back") {
            val scenario = band().pair()
            val before = scenario.band
            scenario.apply(SetMemberArchived("carol", true))
            val change = BandChange.between(before, scenario.band)
            scenario.apply(Undo("op-archive", SetMemberArchived("carol", true), change))
            scenario.partner("bob") shouldBe "carol"
            scenario.partner("carol") shouldBe "bob"
        }

        expect("undoing an older change keeps a pair formed since") {
            val scenario = band()
            val before = scenario.band
            scenario.apply(com.jacekgajek.kapelusz.ops.RenameMember("carol", "Caroline"))
            val change = BandChange.between(before, scenario.band)
            scenario.pair()
            scenario.apply(Undo("op-rename", com.jacekgajek.kapelusz.ops.RenameMember("carol", "Caroline"), change))
            scenario.band.member("carol")!!.name shouldBe "Carol"
            scenario.partner("carol") shouldBe "bob"
        }

        expect("settling together cannot be undone") {
            isUndoable(AcceptSettleTogether("bob", "carol")) shouldBe false
            isUndoable(StopSettlingTogether("bob", "carol")) shouldBe false
            isUndoable(InviteToSettleTogether("bob", "carol")) shouldBe false
        }

        expect("ending a pair stamps the events it keeps settled") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
            scenario.apply(SettleSuggestions("gig"))
            val before = scenario.band.event("gig")!!.updatedAt

            scenario.apply(StopSettlingTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
            scenario.band.event("gig")!!.settledTogether shouldBe listOf(listOf("bob", "carol"))
            withClue("stamped by the operation") { scenario.band.event("gig")!!.updatedAt shouldBe before + 1 }
        }

        expect("a pair is one party on settlement") {
            // Bob collected 400; four equal shares of 100.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
            val settlement = scenario.settle("gig")
            withClue("each still has their own numbers") { settlement.lines.size shouldBe 4 }
            settlement.parties.map { it.partyId } shouldBe listOf("alice", "bob", "dave")
            settlement.party("carol")!!.partnerId shouldBe "carol"
            settlement.party("carol")!!.delta shouldBe pln("200")
            withClue("the pair keeps Carol's 100") {
                settlement.suggestions shouldBe
                    listOf(SuggestedTransfer("bob", "alice", pln("100")), SuggestedTransfer("bob", "dave", pln("100")))
            }
            settlement.suggestionsFor("carol").size shouldBe 2

            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            withClue("still owed on her own, but settled as a pair") { scenario.settle("gig").line("carol")!!.delta shouldBe pln("-100") }
        }

        expect("without the partner a member settles alone") {
            val scenario = band().pair().event("gig", "Alice", "Bob")
            scenario.apply(AddHolding("gig", "h", "bob", pln("200"), DOOR))
            val settlement = scenario.settle("gig")
            settlement.partners.shouldBeEmpty()
            settlement.suggestions shouldBe listOf(SuggestedTransfer("bob", "alice", pln("100")))
        }

        expect("a pair pays out of what each holds") {
            // Bob and Carol collected 100 each; Alice is owed it all as a fixed share.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
            scenario.apply(AddHolding("gig", "h1", "bob", pln("100"), DOOR))
            scenario.apply(AddHolding("gig", "h2", "carol", pln("100"), DOOR))
            scenario.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "alice", pln("150")))
            val settlement = scenario.settle("gig")
            settlement.suggestions shouldBe listOf(SuggestedTransfer("bob", "alice", pln("150")))

            val carol = scenario.userFor("carol", Role.MEMBER)
            scenario.apply(RecordTransfer("gig", "t", "carol", "alice", pln("150"), together = true), carol)
            val transfers = scenario.band.event("gig")!!.transfers.map { Triple(it.id, it.from, it.amount) }
            transfers shouldBe listOf(Triple("t", "bob", pln("100")), Triple("t-1", "carol", pln("50")))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("the treasurer's pair hands the fund's part to the treasurer") {
            // Carol collected 400; 100 goes to the fund Bob holds, 100 to each of the three.
            val scenario = band()
            scenario.apply(SetTreasurer("bob"))
            scenario.pair().event("gig", "Alice", "Bob", "Carol")
            scenario.apply(AddHolding("gig", "h", "carol", pln("400"), DOOR))
            scenario.apply(SetFundAmount("gig", pln("100")))
            scenario.settle("gig").suggestions shouldBe
                listOf(SuggestedTransfer("bob", BAND_FUND, pln("100")), SuggestedTransfer("bob", "alice", pln("100")))
            scenario.apply(SettleSuggestions("gig"))
            val transfers = scenario.band.event("gig")!!.transfers.map { Triple(it.from, it.to, it.amount) }
            transfers shouldBe listOf(Triple("bob", BAND_FUND, pln("100")), Triple("carol", "alice", pln("100")))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("either partner settles the pair's transfers") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
            val carol = scenario.userFor("carol", Role.MEMBER)
            scenario.apply(SettleSuggestions("gig", "carol"), carol)
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            // Not as a plain transfer of Bob's: only a suggestion's, together.
            scenario.rejected(RecordTransfer("gig", "x", "bob", "dave", pln("1")), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
        }

        expect("settle all splits each transfer against what the ones before left") {
            // Carol (weight 200) is owed 200, Bob 100; Alice and Dave collected 250 each.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(SetWeight("gig", "carol", 200))
            scenario.apply(AddHolding("gig", "h1", "alice", pln("250"), DOOR))
            scenario.apply(AddHolding("gig", "h2", "dave", pln("250"), DOOR))
            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            // Alice's 150 goes to Carol (owed 200), Dave's to Bob, owed more by then.
            scenario.band.event("gig")!!.transfers.map { it.to } shouldBe listOf("carol", "bob")
        }

        expect("settle all never takes more from a partner than they hold") {
            // Bob holds 300, Carol 200; Alice and Dave are owed 250 each.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave")
            scenario.apply(AddHolding("gig", "h1", "bob", pln("300"), DOOR))
            scenario.apply(AddHolding("gig", "h2", "carol", pln("200"), DOOR))
            scenario.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "alice", pln("250")))
            scenario.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "dave", pln("250")))
            scenario.apply(SettleSuggestions("gig"))
            val settlement = scenario.settle("gig")
            settlement.status shouldBe EventStatus.SETTLED
            settlement.line("bob")!!.balance shouldBe pln("0")
            settlement.line("carol")!!.balance shouldBe pln("0")
        }

        expect("ending a pair keeps the events it settled settled") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol", "Dave").event("open", "Alice", "Bob", "Carol")
            scenario.apply(AddHolding("gig", "h", "bob", pln("400"), DOOR))
            scenario.apply(AddHolding("open", "h", "bob", pln("300"), DOOR))
            scenario.apply(SettleSuggestions("gig"))
            SettleTogether.openEventsTogether(BandView(scenario.band), "bob", "carol") shouldBe 1
            scenario.apply(StopSettlingTogether("bob", "carol"), scenario.userFor("bob", Role.MEMBER))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            scenario.band.event("gig")!!.settledTogether shouldBe listOf(listOf("bob", "carol"))
            scenario.settle("open").suggestions shouldBe
                listOf(SuggestedTransfer("bob", "alice", pln("100")), SuggestedTransfer("bob", "carol", pln("100")))
            // Also when the pair ends by itself.
            val archived = band().pair().event("gig", "Alice", "Bob", "Carol")
            archived.apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
            archived.apply(SettleSuggestions("gig"))
            archived.apply(SetMemberArchived("carol", true))
            archived.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("a partner acts only where both play") {
            val scenario = band().pair().event("gig", "Alice", "Bob", "Dave")
            scenario.apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
            val carol = scenario.userFor("carol", Role.MEMBER)
            scenario.rejected(SettleSuggestions("gig", "bob"), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.rejected(
                RecordTransfer("gig", "x", "bob", "dave", pln("50"), together = true),
                carol,
            ).reason shouldBe RejectionReason.ROLE_REQUIRED
        }

        expect("undoing an end keeps an invitation sent since") {
            val scenario = band()
            scenario.userFor("dave", Role.MEMBER)
            scenario.pair()
            val before = scenario.band
            scenario.apply(SetMemberRole("carol", Role.VIEWER))
            val change = BandChange.between(before, scenario.band)
            scenario.apply(InviteToSettleTogether("bob", "dave"), scenario.userFor("bob", Role.MEMBER))
            // Bringing the pair back would leave Bob both paired and inviting: refused rather than dropping the invitation.
            scenario.rejected(Undo("op-role", SetMemberRole("carol", Role.VIEWER), change)).reason shouldBe RejectionReason.CHANGED_SINCE
            scenario.band.member("bob")!!.invitedToSettle shouldBe "dave"
        }

        expect("a stored pair with a viewer no longer counts") {
            // No change has tidied it yet.
            val scenario = band().pair().event("gig", "Alice", "Bob", "Carol")
            val stale = scenario.band.copy(
                members = scenario.band.members.map { if (it.id == "carol") it.copy(role = Role.VIEWER) else it },
            )
            com.jacekgajek.kapelusz.settlement.SettlementCalculator.settle(stale, stale.event("gig")!!).partners.shouldBeEmpty()
        }

        // The members' stored order changes neither a pair's suggestions nor how its transfers are split between the two.
        expect("roster order changes nothing for a pair") {
            fun settled(reorder: Boolean): Pair<List<SuggestedTransfer>, List<Triple<String, String, String>>> {
                val scenario = band().pair()
                    .event("e", "Alice", "Bob", "Carol")
                    .apply(AddHolding("e", "h", "alice", pln("300"), DOOR))
                if (reorder) scenario.storeMembersInOrder("carol")
                val suggestions = scenario.settle("e").suggestions
                scenario.apply(SettleSuggestions("e"))
                return suggestions to scenario.band.event("e")!!.transfers.map { Triple(it.from, it.to, it.amount.toString()) }
            }
            val before = settled(reorder = false)
            before.first shouldBe listOf(SuggestedTransfer("alice", "bob", pln("200")))
            settled(reorder = true) shouldBe before
        }
    })
