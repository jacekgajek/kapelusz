package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.allAttachments
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LimitsTest :
    ExpectSpec({

        expect("amounts have an upper bound and the largest one still settles") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
            val tooMuch = Money(Limits.MAX_AMOUNT + 1)
            scenario.rejected(AddHolding("gig", "h", "alice", tooMuch, IncomeSource.HAT, null)).reason shouldBe
                RejectionReason.INVALID_AMOUNT
            scenario.rejected(AddCost("c", "alice", tooMuch, DATE)).reason shouldBe RejectionReason.INVALID_AMOUNT
            // The largest allowed amounts, many times over and with the largest share, settle without overflow.
            repeat(20) { scenario.apply(AddHolding("gig", "h$it", "alice", Money(Limits.MAX_AMOUNT), IncomeSource.HAT, null)) }
            scenario.apply(com.jacekgajek.kapelusz.ops.SetWeight("gig", "alice", 10_000))
            val settlement = SettlementCalculator.settle(scenario.band, scenario.band.event("gig")!!)
            settlement.lines.fold(Money.ZERO) { sum, line -> sum + line.target } shouldBe Money(Limits.MAX_AMOUNT * 20)
        }

        expect("ids are checked and the band fund's id is reserved") {
            val scenario = Scenario("Alice").event("gig", "Alice")
            scenario.rejected(CreateEvent("x\r\nURL:https://evil", "Gig", DATE)).reason shouldBe RejectionReason.INVALID_INPUT
            scenario.rejected(AddGuest("gig", "band-fund", "Fund?")).reason shouldBe RejectionReason.INVALID_INPUT
            scenario.rejected(AddMember("band-fund", "Fund?")).reason shouldBe RejectionReason.INVALID_INPUT
            scenario.apply(AddGuest("gig", "carol", "Carol"))
            withClue("a guest's id") { scenario.rejected(AddMember("carol", "Carol")).reason shouldBe RejectionReason.INVALID_INPUT }
        }

        expect("texts are bounded") {
            val scenario = Scenario("Alice").event("gig", "Alice")
            scenario.rejected(UpdateEvent("gig", name = Change("x".repeat(Limits.MAX_NAME + 1)))).reason shouldBe
                RejectionReason.INVALID_INPUT
            scenario.apply(UpdateEvent("gig", name = Change("x".repeat(Limits.MAX_NAME))))
            scenario.rejected(AddCost("c", "alice", Money(100), DATE, description = "d".repeat(Limits.MAX_TEXT + 1))).reason shouldBe
                RejectionReason.INVALID_INPUT
        }

        expect("parsing refuses numbers that would overflow") {
            Money.parse("9999999999999") shouldBe Money(999_999_999_999_900)
            shouldThrow<IllegalArgumentException> { Money.parse("99999999999999999") }
        }

        expect("a guest cannot take a member's id and old long texts do not block other changes") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice")
            scenario.rejected(AddGuest("gig", "bob", "Bob?")).reason shouldBe RejectionReason.DUPLICATE_ID
            // Notes longer than today's limit (from before it): other changes to the event still work.
            val long = scenario.band.copy(events = scenario.band.events.map { it.copy(notes = "n".repeat(Limits.MAX_NOTES + 10)) })
            val applied = Engine.apply(
                long,
                com.jacekgajek.kapelusz.rules.Actor.System,
                AddHolding("gig", "h", "alice", Money(100), IncomeSource.HAT, null),
                "op",
                1,
            )
            applied.shouldBeInstanceOf<ApplyResult.Applied>()
        }

        expect("negative amounts are bounded too") {
            val scenario = Scenario("Alice").event("gig", "Alice").apply(com.jacekgajek.kapelusz.ops.SetTreasurer("alice"))
            scenario.rejected(com.jacekgajek.kapelusz.ops.SetFundAmount("gig", Money(Long.MIN_VALUE))).reason shouldBe
                RejectionReason.INVALID_AMOUNT
        }

        expect("attachment ids are unique in the whole band") {
            val scenario = Scenario("Alice").apply(AddCost("c1", "alice", Money(100), DATE)).apply(AddCost("c2", "alice", Money(100), DATE))
            val file = Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 10)
            scenario.apply(AddAttachment("c1", file))
            scenario.rejected(AddAttachment("c2", file)).reason shouldBe RejectionReason.DUPLICATE_ID
        }

        expect("attachment files across the band cannot exceed the byte budget") {
            val scenario = Scenario("Alice").event("gig", "Alice")
            (1..39).forEach { costId ->
                scenario.apply(AddCost("c$costId", "alice", Money(100), DATE))
            }
            val maxPdfBytes = AttachmentKind.PDF.maxBytes
            val sizes = List(200) { maxPdfBytes }

            sizes.forEachIndexed { index, sizeBytes ->
                val owner = if (index < 10) {
                    AttachmentOwner.OfEvent("gig")
                } else {
                    AttachmentOwner.OfCost("c${1 + (index - 10) / 5}")
                }
                scenario.apply(AddAttachment(owner, Attachment("a$index", AttachmentKind.PDF, sizeBytes = sizeBytes)))
            }
            scenario.band.allAttachments().sumOf { it.sizeBytes ?: 0L } shouldBe Limits.MAX_STORAGE_BYTES

            scenario.rejected(
                AddAttachment(AttachmentOwner.OfCost("c39"), Attachment("over", AttachmentKind.PDF, sizeBytes = 1)),
            ).reason shouldBe RejectionReason.LIMIT_REACHED
        }

        expect("the attachment byte budget uses a wide sum for legacy over-limit bands") {
            val costs = (0 until 42).map { costIndex ->
                Cost(
                    id = "c$costIndex",
                    memberId = "alice",
                    amount = Money(100),
                    date = DATE,
                    attachments = List(5) { attachmentIndex ->
                        Attachment("a${costIndex}_$attachmentIndex", AttachmentKind.PDF, sizeBytes = AttachmentKind.PDF.maxBytes)
                    },
                )
            }
            val before = Scenario("Alice").band.copy(costs = costs)
            val addedCost = Cost(
                id = "c42",
                memberId = "alice",
                amount = Money(100),
                date = DATE,
                attachments = listOf(Attachment("a42_0", AttachmentKind.PDF, sizeBytes = 1)),
            )
            val after = before.copy(costs = before.costs + addedCost)

            Limits.violation(before, after)?.reason shouldBe RejectionReason.LIMIT_REACHED
        }
    })
