package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.CONTRACT
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.sum
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.EventStatus
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe

/** A fee the organiser has not paid yet. */
class AwaitedFeeTest :
    ExpectSpec({

        expect("the fee counts in shares but only money held moves") {
            val scenario = gig()
            val open = scenario.settle("gig")
            open.income shouldBe pln("5000")
            open.status shouldBe EventStatus.OPEN
            // Bob passes on only his surplus over his share; nobody is told to pay the fee.
            withClue(open.suggestions.toString()) { open.suggestions.forAll { it.from shouldBe "bob" } }
            open.suggestions.fold(pln("0")) { sum, it -> sum + it.amount } shouldBe open.line("bob")!!.delta

            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.AWAITING
            scenario.rejected(SetEventArchived("gig", true)).reason shouldBe RejectionReason.EVENT_UNSETTLED

            scenario.apply(ReceiveAwaited("gig", "fee", "paid", "alice", pln("3000")))
            val paid = scenario.settle("gig")
            paid.status shouldBe EventStatus.OPEN
            paid.suggestions.forAll { it.from shouldBe "alice" }
            scenario.apply(SettleSuggestions("gig")).apply(SetEventArchived("gig", true))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("part of the fee can come and the contract follows the last part") {
            val scenario = gig().apply(
                AddAttachment(AttachmentOwner.OfHolding("gig", "fee"), Attachment("contract", AttachmentKind.PDF, "contract.pdf", 1_000)),
            )
            scenario.apply(ReceiveAwaited("gig", "fee", "first", "carol", pln("1000")))
            val event = scenario.band.event("gig")!!
            event.awaited shouldBe pln("2000")
            withClue("still with what is awaited") {
                event.holdings.single { it.id == "fee" }.attachments.map { it.id } shouldBe listOf("contract")
            }
            scenario.rejected(ReceiveAwaited("gig", "fee", "x", "carol", pln("2001"))).reason shouldBe RejectionReason.INVALID_AMOUNT
            scenario.apply(ReceiveAwaited("gig", "fee", "rest", "carol", pln("2000")))
            val after = scenario.band.event("gig")!!
            after.awaited shouldBe pln("0")
            after.holdings.single { it.id == "rest" }.attachments.map { it.id } shouldBe listOf("contract")
            withClue("income unchanged by the arrival") { scenario.settle("gig").income shouldBe pln("5000") }
        }

        expect("paid to the band's account") {
            val scenario = gig().apply(SetTreasurer("alice")).apply(ReceiveAwaited("gig", "fee", "paid", BAND_FUND, pln("3000")))
            val settlement = scenario.settle("gig")
            settlement.lines.any { it.partyId == BAND_FUND } shouldBe true
            withClue("paid out of the fund") { settlement.suggestions.any { it.from == BAND_FUND } shouldBe true }
            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
            withClue("paid out in full") { BandFund.balance(scenario.band) shouldBe pln("0") }
            BandFund.isHandedOver(scenario.band.event("gig")!!) shouldBe true
        }

        expect("leads record it and who got it receives it") {
            val scenario = gig()
            val bob = scenario.userFor("bob", Role.MEMBER)
            val alice = scenario.userFor("alice", Role.OWNER)
            scenario.userFor("carol", Role.ARTIST)
            scenario.rejected(
                AddHolding("gig", "f2", ORGANISER, pln("1"), CONTRACT),
                bob,
            ).reason shouldBe RejectionReason.ROLE_REQUIRED
            // Paid to Bob: he says it arrived, but not that someone else got it.
            scenario.rejected(
                ReceiveAwaited("gig", "fee", "x", "alice", pln("1")),
                bob,
            ).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.rejected(
                ReceiveAwaited("gig", "fee", "x", BAND_FUND, pln("1")),
                bob,
            ).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.apply(ReceiveAwaited("gig", "fee", "by-bob", "bob", pln("1")), bob)
            scenario.rejected(
                ReceiveAwaited("gig", "fee", "x", "carol", pln("1")),
                alice,
            ).reason shouldBe RejectionReason.ARTIST_HOLDS_NO_MONEY
            scenario.apply(AddHolding("gig", "f2", ORGANISER, pln("100"), CONTRACT), alice)
            withClue("the lead to remind") {
                scenario.band.event("gig")!!.holdings.single { it.id == "f2" }.recordedBy shouldBe "alice"
            }
            // Received by Bob, it is still the lead's record: he can't make the fee or its contract vanish.
            scenario.apply(ReceiveAwaited("gig", "f2", "f2-bob", "bob", pln("100")), bob)
            scenario.rejected(RemoveHolding("gig", "f2-bob"), bob).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.rejected(
                UpdateHolding("gig", "f2-bob", amount = Change(pln("2"))),
                bob,
            ).reason shouldBe RejectionReason.ROLE_REQUIRED
            withClue("not an awaited fee") {
                scenario.rejected(ReceiveAwaited("gig", "door", "x", "alice", pln("1"))).reason shouldBe RejectionReason.INVALID_INPUT
            }
        }

        expect("receiving can be undone") {
            val scenario = gig()
            val before = scenario.band
            val receive = ReceiveAwaited("gig", "fee", "paid", "alice", pln("3000"))
            scenario.apply(receive)
            scenario.apply(Undo("u", receive, BandChange.between(before, scenario.band)))
            scenario.band.event("gig")!!.awaited shouldBe pln("3000")
        }

        expect("receiving is checked") {
            val scenario = gig()
            withClue("fund off") {
                scenario.rejected(ReceiveAwaited("gig", "fee", "x", BAND_FUND, pln("1"))).reason shouldBe RejectionReason.NO_TREASURER
            }
            scenario.rejected(ReceiveAwaited("gig", "fee", "x", "nobody", pln("1"))).reason shouldBe RejectionReason.NOT_A_PARTICIPANT
            scenario.rejected(ReceiveAwaited("gig", "fee", "door", "alice", pln("1"))).reason shouldBe RejectionReason.DUPLICATE_ID
            scenario.rejected(ReceiveAwaited("gig", "fee", "x", "alice", pln("0"))).reason shouldBe RejectionReason.INVALID_AMOUNT
            withClue("a reserved id") {
                scenario.rejected(com.jacekgajek.kapelusz.ops.AddMember(ORGANISER, "Org")).reason shouldBe RejectionReason.INVALID_INPUT
            }
        }

        expect("a fee held by the fund keeps it in use") {
            val scenario = gig().apply(SetTreasurer("alice")).apply(ReceiveAwaited("gig", "fee", "paid", BAND_FUND, pln("3000")))
            BandFund.balance(scenario.band) shouldBe pln("3000")
            withClue("to pay out") { BandFund.notHandedOver(scenario.band) shouldBe pln("-3000") }
            scenario.rejected(com.jacekgajek.kapelusz.ops.DisableBandFund).reason shouldBe RejectionReason.FUND_NOT_EMPTY
        }
    })

/** Bob took 2000 at the door; the 3000 contract is awaited; three equal shares of 1666.67 (rounding aside). */
private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
    .apply(AddHolding("gig", "door", "bob", pln("2000"), DOOR))
    .apply(AddHolding("gig", "fee", ORGANISER, pln("3000"), CONTRACT))
