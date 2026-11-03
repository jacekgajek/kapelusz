package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.settlement.CostStatus
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.view.BandView
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Covers artists, and costs that wait for a lead's Refund here. */
class ArtistAndWaitingCostsTest :
    ExpectSpec({

        // The bug from 2026-10-27: paid, then a refund added made the artist's share smaller; they hand the difference back.
        expect("an artist paid before a later refund hands the difference back") {
            val scenario = gig()
            val alice = scenario.userFor("alice", Role.OWNER)
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.userFor("carol", Role.ARTIST)
            scenario.apply(AddHolding("gig", "hat", "alice", pln("300"), DOOR), alice)
            // Everyone settled but Bob: Carol got her 100.
            scenario.apply(RecordTransfer("gig", "to-carol", "alice", "carol", pln("100")), alice)
            // Bob's cost, accepted for this gig: every share drops to 90, so Carol was paid 10 too much.
            scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
            scenario.apply(SetRefund("gig", "bobs", pln("30")), alice)

            scenario.apply(SettleSuggestions("gig"), alice)
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            val back = scenario.band.event("gig")!!.transfers.filter { it.from == "carol" }
            withClue("Carol hands back what she was paid too much") { back.map { it.amount } shouldBe listOf(pln("10")) }
        }

        expect("an artist sees only their own transfers") {
            val scenario = gig()
            val alice = scenario.userFor("alice", Role.OWNER)
            scenario.userFor("carol", Role.ARTIST)
            scenario.apply(AddHolding("gig", "hat", "alice", pln("600"), DOOR), alice)
            val owed = Artists.ownTransfers(scenario.band, scenario.settle("gig"), "carol")
            withClue("not Alice → Bob") {
                owed.map { Triple(it.from, it.to, it.amount) } shouldBe listOf(Triple("alice", "carol", pln("200")))
            }

            // Paid, then Bob's refund makes her share smaller: she sees whom she hands back to.
            scenario.apply(RecordTransfer("gig", "to-carol", "alice", "carol", pln("200")), alice)
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
            scenario.apply(SetRefund("gig", "bobs", pln("30")), alice)
            val back = Artists.ownTransfers(scenario.band, scenario.settle("gig"), "carol")
            withClue("to Bob, who is owed") {
                back.map { Triple(it.from, it.to, it.amount) } shouldBe listOf(Triple("carol", "bob", pln("10")))
            }
        }

        expect("artists mark their own transfers paid or received") {
            val scenario = Scenario("Lead", "Alice", "Bob", "Dane").event("gig", "Alice", "Bob", "Dane")
            val alice = scenario.userFor("alice", Role.MEMBER)
            val bob = scenario.userFor("bob", Role.ARTIST)
            val dane = scenario.userFor("dane", Role.ARTIST)
            scenario.apply(AddHolding("gig", "hat", "alice", pln("300"), DOOR), alice)
            scenario.apply(SettleSuggestions("gig", "alice"), alice)
            // Bob's cost, accepted: Alice owes him his refund's share and Dane hands back what he got too much.
            scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
            scenario.apply(SetRefund("gig", "bobs", pln("30")))
            val owed = scenario.settle("gig").suggestions.map { Triple(it.from, it.to, it.amount) }
            owed.sortedBy { it.first } shouldBe listOf(Triple("alice", "bob", pln("10")), Triple("dane", "bob", pln("10")))

            // Each artist in it marks it, without a lead; nobody else's.
            scenario.rejected(RecordTransfer("gig", "x", "alice", "bob", pln("10")), dane).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.apply(RecordTransfer("gig", "t1", "dane", "bob", pln("10")), dane)
            scenario.apply(RecordTransfer("gig", "t2", "alice", "bob", pln("10")), bob)
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("an artist hands back no more than they owe") {
            val scenario = gig()
            val alice = scenario.userFor("alice", Role.OWNER)
            scenario.userFor("carol", Role.ARTIST)
            scenario.apply(AddHolding("gig", "hat", "alice", pln("300"), DOOR), alice)
            scenario.apply(RecordTransfer("gig", "to-carol", "alice", "carol", pln("100")), alice)
            // Settled with Carol: she owes nothing, so passing money on is still refused.
            scenario.rejected(
                RecordTransfer("gig", "from-carol", "carol", "bob", pln("10")),
                alice,
            ).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY
            // A refund makes her owe 10: no more than that, and exactly that.
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(AddCost("bobs", "bob", pln("30"), DATE, incurredForEventId = "gig"), bob)
            scenario.apply(SetRefund("gig", "bobs", pln("30")), alice)
            scenario.rejected(
                RecordTransfer("gig", "back-11", "carol", "bob", pln("11")),
                alice,
            ).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY
            scenario.apply(RecordTransfer("gig", "back-10", "carol", "bob", pln("10")), alice)
        }

        expect("only a lead's cost entry is included automatically") {
            val scenario = gig()
            val bob = scenario.userFor("bob", Role.MEMBER)
            val alice = scenario.userFor("alice", Role.OWNER)
            scenario.apply(AddCost("bobs", "bob", pln("40"), DATE, incurredForEventId = "gig"), bob)
            withClue("a member's cost waits") { scenario.band.event("gig")!!.refundOf("bobs").shouldBeNull() }
            CostLedger.pendingFromEarlier(scenario.band, scenario.band.event("gig")!!).map { it.id } shouldBe listOf("bobs")
            BandView(scenario.band).costStatuses.status(scenario.band.cost("bobs")!!) shouldBe CostStatus.PENDING

            scenario.apply(AddCost("for-carol", "carol", pln("30"), DATE, incurredForEventId = "gig"), alice)
            withClue("a lead's entry, even for someone else") {
                scenario.band.event("gig")!!.refundOf("for-carol")!!.amount shouldBe pln("30")
            }
            withClue("and only that cost") { scenario.band.event("gig")!!.refundOf("bobs").shouldBeNull() }

            // Moving a member's cost to another event does not include it there either.
            scenario.apply(
                CreateEvent("other", "other", DATE, participants = listOf(com.jacekgajek.kapelusz.model.Participant("bob"))),
                alice,
            )
            scenario.apply(UpdateCost("bobs", incurredForEventId = Change("other")), bob)
            scenario.band.event("other")!!.refundOf("bobs").shouldBeNull()
        }

        expect("creating an event or adding someone includes nothing") {
            val scenario = Scenario("Alice", "Bob").event("first", "Alice", "Bob")
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(AddCost("c", "bob", pln("40"), DATE), bob)
            scenario.event("gig", "Alice", "Bob")
            scenario.band.event("gig")!!.refunds.shouldBeEmpty()
            scenario.event("later", "Alice").apply(AddParticipant("later", "bob"))
            scenario.band.event("later")!!.refunds.shouldBeEmpty()
        }

        expect("artists look after their own costs only") {
            val scenario = gig()
            val carol = scenario.userFor("carol", Role.ARTIST)
            scenario.apply(AddCost("own", "carol", pln("20"), DATE, incurredForEventId = "gig"), carol)
            scenario.apply(AddCost("general", "carol", pln("5"), DATE), carol)
            scenario.apply(UpdateCost("own", amount = Change(pln("25"))), carol)
            withClue("waits for a lead") { scenario.band.event("gig")!!.refundOf("own").shouldBeNull() }
            scenario.rejected(AddCost("x", "bob", pln("5"), DATE), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.rejected(AddHolding("gig", "h", "carol", pln("100"), DOOR), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.rejected(SettleSuggestions("gig", "carol"), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
        }

        expect("artists never hold money") {
            val scenario = gig()
            scenario.userFor("carol", Role.ARTIST)
            scenario.rejected(AddHolding("gig", "h", "carol", pln("100"), DOOR)).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY
            scenario.rejected(SetTreasurer("carol")).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY

            // They are only paid: someone who collected pays them, and either of the two records it.
            scenario.apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
            val carol = scenario.userFor("carol", Role.ARTIST)
            withClue("not someone else's") {
                scenario.rejected(
                    RecordTransfer("gig", "t", "alice", "bob", pln("100")),
                    carol,
                ).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            scenario.apply(RecordTransfer("gig", "t", "alice", "carol", pln("100")), carol)
        }

        expect("someone holding money cannot become an artist") {
            val scenario = gig().apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
            scenario.rejected(SetMemberRole("bob", Role.ARTIST)).reason shouldBe RejectionReason.HOLDS_MONEY
            scenario.rejected(LinkUser("bob", "user-bob", Role.ARTIST)).reason shouldBe RejectionReason.HOLDS_MONEY
            scenario.apply(SettleSuggestions("gig")).apply(LinkUser("bob", "user-bob", Role.ARTIST))
            withClue("settled events don't matter") { scenario.band.member("bob")!!.role shouldBe Role.ARTIST }

            scenario.apply(SetTreasurer("carol")).apply(LinkUser("carol", "user-carol", Role.MEMBER))
            withClue("the band fund holder") {
                scenario.rejected(SetMemberRole("carol", Role.ARTIST)).reason shouldBe RejectionReason.HOLDS_MONEY
            }
        }

        expect("only a lead's edit raises an included refund") {
            val scenario = gig()
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"), bob).apply(SetRefund("gig", "c", pln("40")))
            scenario.apply(UpdateCost("c", amount = Change(pln("30"))), bob)
            withClue("lowering follows") { scenario.band.event("gig")!!.refundOf("c")!!.amount shouldBe pln("30") }
            scenario.apply(UpdateCost("c", amount = Change(pln("400"))), bob)
            val gig = scenario.band.event("gig")!!
            withClue("the rest waits for a lead") { gig.refundOf("c")!!.amount shouldBe pln("30") }
            withClue("in this event's Waiting list") {
                CostLedger.pendingFromEarlier(scenario.band, gig).map { it.id } shouldBe listOf("c")
            }
            withClue("what Refund here sets") {
                CostLedger.maxRefundAt(scenario.band, scenario.band.cost("c")!!, gig) shouldBe pln("400")
            }
        }

        // An artist who ends up owing (overpaid, or owing from before) hands it back and records it.
        expect("an artist records what they hand back") {
            val scenario = gig().apply(AddHolding("gig", "h", "alice", pln("300"), DOOR))
                .apply(AddCost("c", "carol", pln("50"), DATE)).apply(SetRefund("gig", "c", pln("50"))).apply(SettleSuggestions("gig"))
            val carol = scenario.userFor("carol", Role.ARTIST)
            val alice = scenario.userFor("alice", Role.OWNER)
            withClue("never more than owed") {
                scenario.rejected(
                    RecordTransfer("gig", "over", "alice", "carol", pln("1")),
                    alice,
                ).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY
            }
            // The refund taken back after it was paid: she got too much (less her bigger share now).
            scenario.apply(SetRefund("gig", "c", pln("0")), alice)
            val owes = scenario.settle("gig").line("carol")!!.delta
            withClue("overpaid: owes some back") { owes.isPositive shouldBe true }
            scenario.rejected(
                RecordTransfer("gig", "more", "carol", "alice", owes + pln("1")),
                carol,
            ).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY
            scenario.apply(RecordTransfer("gig", "back", "carol", "alice", owes), carol).apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("artists look after their own receipts") {
            val scenario = gig()
            val carol = scenario.userFor("carol", Role.ARTIST)
            scenario.apply(AddCost("own", "carol", pln("20"), DATE), carol).apply(AddCost("bobs", "bob", pln("20"), DATE))
            val photo = Attachment("a", AttachmentKind.IMAGE, sizeBytes = 100)
            scenario.apply(AddAttachment("own", photo), carol).apply(RemoveAttachment("own", "a"), carol)
            scenario.rejected(AddAttachment("bobs", photo), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
        }
    })

private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
