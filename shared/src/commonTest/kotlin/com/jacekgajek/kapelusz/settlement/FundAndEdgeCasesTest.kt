package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.settlement.CostStatus
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.SettlementProblem
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import com.jacekgajek.kapelusz.view.BandView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class FundAndEdgeCasesTest :
    ExpectSpec({

        context("band fund") {
            // Third way out of a loss: the band fund covers the shortfall and pays out through the treasurer.
            expect("a negative amount covers a loss") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
                    .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
                    .apply(AddCost("c", "bob", pln("150"), DATE, incurredForEventId = "e"))
                scenario.settle("e").status shouldBe EventStatus.LOSS

                scenario.apply(SetFundAmount("e", pln("-50")))
                val s = scenario.settle("e")
                s.net shouldBe pln("0")
                s.line(BAND_FUND)!!.target shouldBe pln("-50")
                s.suggestions shouldBe
                    listOf(SuggestedTransfer("alice", "bob", pln("100")), SuggestedTransfer(BAND_FUND, "bob", pln("50")))
                scenario.apply(SettleSuggestions("e"))
                scenario.settle("e").status shouldBe EventStatus.SETTLED
                withClue("the fund may go negative") { BandFund.balance(scenario.band) shouldBe pln("-50") }
            }

            expect("a cost is refunded from it") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice"))
                    .apply(AddCost("c", "bob", pln("322"), DATE))
                    .apply(AddFundEntry("f", DATE, "Refund Bob's train", pln("-300"), costId = "c"))
                val cost = { scenario.band.cost("c")!! }
                BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.PARTLY_REFUNDED
                CostLedger.outstanding(scenario.band, cost()) shouldBe pln("22")
                BandFund.balance(scenario.band) shouldBe pln("-300")

                scenario.rejected(UpdateFundEntry("f", amount = Change(pln("-323")))).code shouldBe RejectionCode.INVALID
                scenario.rejected(UpdateFundEntry("f", amount = Change(pln("10")))).code shouldBe RejectionCode.INVALID
                scenario.apply(UpdateFundEntry("f", amount = Change(pln("-322"))))
                BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.REFUNDED

                scenario.rejected(AddFundEntry("g", DATE, "again", pln("-1"), costId = "c")).code shouldBe RejectionCode.INVALID
            }

            expect("it can be turned off only when it is empty and nothing is owed") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
                    .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
                    .apply(SetFundAmount("e", pln("100")))
                withClue("the event still owes the fund 100") {
                    scenario.rejected(DisableBandFund).reason shouldBe RejectionReason.FUND_NOT_EMPTY
                }

                scenario.apply(SettleSuggestions("e"))
                BandFund.balance(scenario.band) shouldBe pln("100")
                withClue("the fund holds 100") { scenario.rejected(DisableBandFund).reason shouldBe RejectionReason.FUND_NOT_EMPTY }

                // Spent on the band: empty again, though it had transfers in the past.
                scenario.apply(AddCost("van", "bob", pln("100"), DATE))
                    .apply(AddFundEntry("f", DATE, "Van rental", pln("-100"), costId = "van"))
                scenario.apply(DisableBandFund)
                scenario.band.treasurerMemberId.shouldBeNull()
                withClue("a settled event stays settled without the fund") { scenario.settle("e").status shouldBe EventStatus.SETTLED }
                scenario.settle("e").problems.shouldBeEmpty()

                scenario.apply(SetTreasurer("bob"))
                withClue("choosing a holder turns it on again") { scenario.band.treasurerMemberId shouldBe "bob" }
            }

            expect("owed amounts of different events do not cancel out") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice"))
                    .event("a", "Alice", "Bob").apply(AddHolding("a", "h1", "bob", pln("300"), DOOR)).apply(SetFundAmount("a", pln("100")))
                    .event("b", "Alice", "Bob").apply(AddHolding("b", "h2", "bob", pln("300"), DOOR)).apply(SetFundAmount("b", pln("-100")))
                BandFund.notHandedOver(scenario.band) shouldBe pln("0")
                scenario.rejected(DisableBandFund).reason shouldBe RejectionReason.FUND_NOT_EMPTY
            }

            // With the fund off, nothing may leave it holding or owing money.
            expect("once disabled it cannot be changed back into use") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
                    .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
                    .apply(SetFundAmount("e", pln("100")))
                    .apply(SettleSuggestions("e"))
                    .apply(AddCost("van", "bob", pln("100"), DATE))
                    .apply(AddFundEntry("f", DATE, "Van rental", pln("-100"), costId = "van"))
                    .apply(DisableBandFund)
                val toFund = scenario.band.event("e")!!.transfers.first { it.to == BAND_FUND }

                scenario.rejected(UpdateFundEntry("f", amount = Change(pln("-50")))).reason shouldBe RejectionReason.NO_TREASURER
                scenario.rejected(RemoveFundEntry("f")).reason shouldBe RejectionReason.NO_TREASURER
                scenario.rejected(RemoveTransfer("e", toFund.id)).reason shouldBe RejectionReason.NO_TREASURER
                scenario.rejected(SetFundAmount("e", pln("50"))).reason shouldBe RejectionReason.NO_TREASURER
                scenario.apply(UpdateFundEntry("f", description = Change("Van")))
                withClue("changes that keep it empty are fine") { scenario.band.fundEntries.single().description shouldBe "Van" }
            }

            // An event the fund pays for, with nothing collected, can be settled.
            expect("an event paid only by it is not a draft") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice")).event("e", "Alice", "Bob")
                    .apply(AddCost("c", "bob", pln("80"), DATE, incurredForEventId = "e"))
                withClue("a member's cost alone does not open the event") { scenario.settle("e").status shouldBe EventStatus.DRAFT }

                scenario.apply(SetFundAmount("e", pln("-80")))
                val s = scenario.settle("e")
                s.status shouldBe EventStatus.OPEN
                s.suggestions shouldBe listOf(SuggestedTransfer(BAND_FUND, "bob", pln("80")))
                scenario.apply(SettleSuggestions("e"))
                scenario.settle("e").status shouldBe EventStatus.SETTLED
            }

            // Money comes in from someone (a member, or outside the band) and leaves only to refund a cost.
            expect("its entries have a payer or a cost") {
                val scenario = Scenario("Alice", "Bob").apply(SetTreasurer("alice"))
                scenario.apply(AddFundEntry("fee", DATE, "Membership fee", pln("50"), paidBy = "bob"))
                scenario.apply(AddFundEntry("grant", DATE, "Grant", pln("500")))
                scenario.band.fundEntries.map { it.paidBy } shouldBe listOf("bob", null)
                withClue("out only for a cost") {
                    scenario.rejected(AddFundEntry("x", DATE, "Strings", pln("-40"))).reason shouldBe RejectionReason.INVALID_INPUT
                }
                scenario.rejected(AddFundEntry("x", DATE, "Fee", pln("5"), paidBy = "nobody")).reason shouldBe RejectionReason.NOT_FOUND
                scenario.apply(AddCost("strings", "bob", pln("40"), DATE))
                scenario.rejected(
                    AddFundEntry("x", DATE, "Strings", pln("-40"), costId = "strings", paidBy = "bob"),
                ).reason shouldBe RejectionReason.INVALID_INPUT
                scenario.apply(AddFundEntry("r", DATE, "Strings", pln("-40"), costId = "strings"))

                // The payer can change (to outside the band too), the direction can't.
                scenario.apply(UpdateFundEntry("fee", paidBy = Change("alice")))
                scenario.apply(UpdateFundEntry("grant", paidBy = Change(null)))
                scenario.band.fundEntries.first { it.id == "fee" }.paidBy shouldBe "alice"
                scenario.apply(UpdateFundEntry("fee", paidBy = Change(null)))
                scenario.band.fundEntries.first { it.id == "fee" }.paidBy.shouldBeNull()
                scenario.rejected(UpdateFundEntry("fee", amount = Change(pln("-50")))).reason shouldBe RejectionReason.INVALID_AMOUNT
                scenario.rejected(UpdateFundEntry("r", amount = Change(pln("40")))).reason shouldBe RejectionReason.INVALID_AMOUNT
                withClue("a refund's cost names whose") {
                    scenario.rejected(UpdateFundEntry("r", paidBy = Change("bob"))).reason shouldBe RejectionReason.INVALID_INPUT
                }
                scenario.rejected(UpdateFundEntry("fee", paidBy = Change("nobody"))).reason shouldBe RejectionReason.NOT_FOUND
            }
        }

        expect("no shares blocks settlement until weights change") {
            val scenario = Scenario("Alice", "Bob")
                .apply(CreateEvent("e", "e", DATE, participants = listOf(Participant("alice", 0), Participant("bob", 0))))
                .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
            val blocked = scenario.settle("e")
            blocked.problems shouldBe setOf(SettlementProblem.NO_SHARES)
            blocked.canSettle shouldBe false
            blocked.suggestions.shouldBeEmpty()

            scenario.apply(SetWeight("e", "bob", 100))
            scenario.settle("e").suggestions shouldBe listOf(SuggestedTransfer("alice", "bob", pln("100")))
        }

        // A refund assigned in an event counts as paid only once that event is settled.
        expect("a refund is planned until the event is settled") {
            val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
                .apply(AddHolding("e", "h", "alice", pln("100"), DOOR))
                .apply(AddCost("c", "bob", pln("50"), DATE, incurredForEventId = "e"))
            val cost = { scenario.band.cost("c")!! }
            BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.REFUND_PLANNED
            BandView(scenario.band).costStatuses.unpaid(cost()) shouldBe pln("50")
            withClue("already assigned, cannot be assigned again") { CostLedger.outstanding(scenario.band, cost()) shouldBe pln("0") }

            scenario.apply(SettleSuggestions("e"))
            BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.REFUNDED
            BandView(scenario.band).costStatuses.unpaid(cost()) shouldBe pln("0")

            scenario.apply(AddHolding("e", "h2", "alice", pln("10"), DOOR))
            withClue("reopened event: not final any more") {
                BandView(scenario.band).costStatuses.status(cost()) shouldBe CostStatus.REFUND_PLANNED
            }
        }

        // Paying part of a suggested transfer leaves the rest suggested.
        expect("partial settlement leaves a remainder") {
            val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
                .apply(AddHolding("e", "h", "alice", pln("300"), DOOR))
                .apply(RecordTransfer("e", "t1", "alice", "bob", pln("100")))
            scenario.settle("e").suggestions shouldBe listOf(SuggestedTransfer("alice", "bob", pln("50")))
            scenario.settle("e").status shouldBe EventStatus.OPEN

            scenario.apply(RecordTransfer("e", "t2", "alice", "bob", pln("50")))
            scenario.settle("e").status shouldBe EventStatus.SETTLED
        }

        context("archiving") {
            // Archived events are read-only, except restoring and deleting them.
            expect("archived events are read-only") {
                val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
                    .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
                    .apply(SettleSuggestions("e"))
                    .apply(SetEventArchived("e", true))
                scenario.rejected(AddHolding("e", "h2", "bob", pln("1"), DOOR)).reason shouldBe RejectionReason.ARCHIVED_EVENT
                scenario.rejected(SettleSuggestions("e")).reason shouldBe RejectionReason.ARCHIVED_EVENT
                scenario.rejected(
                    AddCost("c", "bob", pln("10"), DATE, incurredForEventId = "e"),
                ).reason shouldBe RejectionReason.ARCHIVED_EVENT

                // Money has moved in it, so the cost's refund is set by hand.
                scenario.apply(SetEventArchived("e", false))
                    .apply(AddCost("c", "bob", pln("10"), DATE, incurredForEventId = "e"))
                    .apply(SetRefund("e", "c", pln("10")))
                withClue("the refund reopened it") {
                    scenario.rejected(SetEventArchived("e", true)).reason shouldBe RejectionReason.EVENT_UNSETTLED
                }
                scenario.apply(SettleSuggestions("e")).apply(SetEventArchived("e", true))
                withClue("its refund is part of the archived event") {
                    scenario.rejected(DeleteCost("c")).reason shouldBe RejectionReason.ARCHIVED_EVENT
                }
                scenario.event("d", "Alice").apply(SetEventArchived("d", true)).apply(DeleteEvent("d"))
                scenario.band.event("d").shouldBeNull()
            }

            // Only events with nothing left to settle can be archived: settled ones and drafts.
            expect("unsettled events cannot be archived") {
                val scenario = Scenario("Alice", "Bob").event("e", "Alice", "Bob")
                    .apply(AddHolding("e", "h", "bob", pln("300"), DOOR))
                scenario.settle("e").status shouldBe EventStatus.OPEN
                scenario.rejected(SetEventArchived("e", true)).reason shouldBe RejectionReason.EVENT_UNSETTLED

                scenario.apply(SetFixedShare("e", "alice", pln("400")))
                scenario.settle("e").status shouldBe EventStatus.LOSS
                scenario.rejected(SetEventArchived("e", true)).reason shouldBe RejectionReason.EVENT_UNSETTLED

                scenario.apply(SetFixedShare("e", "alice", null, weight = 1))
                    .apply(SettleSuggestions("e"))
                    .apply(SetEventArchived("e", true))
                scenario.band.event("e")!!.archived shouldBe true

                scenario.event("draft", "Alice").apply(SetEventArchived("draft", true))
                withClue("a draft has no money in it") { scenario.band.event("draft")!!.archived shouldBe true }

                // A draft with a refund in it would count the cost as covered while nobody pays it.
                scenario.event("d2", "Bob").apply(AddCost("c2", "bob", pln("10"), DATE, incurredForEventId = "d2"))
                scenario.settle("d2").status shouldBe EventStatus.DRAFT
                scenario.rejected(SetEventArchived("d2", true)).reason shouldBe RejectionReason.EVENT_UNSETTLED
            }
        }
    })
