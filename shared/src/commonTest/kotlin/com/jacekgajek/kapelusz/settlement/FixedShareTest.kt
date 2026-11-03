package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.engine.isUndoable
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.sum
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.rules.Permissions
import com.jacekgajek.kapelusz.settlement.EventStatus
import com.jacekgajek.kapelusz.settlement.SettlementProblem
import com.jacekgajek.kapelusz.settlement.SuggestedTransfer
import com.jacekgajek.kapelusz.targets
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Shares as a percentage or a fixed amount. */
class FixedShareTest :
    ExpectSpec({

        expect("a guest can be added with a fixed amount") {
            val scenario = gig()
                .apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
                .apply(AddGuest("gig", "dave", "Dave", fixedShare = pln("100")))
            scenario.band.event("gig")!!.participant("dave")!!.fixedShare shouldBe pln("100")
            scenario.settle("gig").line("dave")!!.share shouldBe pln("100")
            scenario.rejected(AddGuest("gig", "erin", "Erin", fixedShare = pln("0"))).reason shouldBe RejectionReason.INVALID_AMOUNT
        }

        expect("fixed amounts come off the top and the rest is split by weight") {
            val scenario = gig()
                .apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
                .apply(SetFixedShare("gig", "carol", pln("100")))
            val s = scenario.settle("gig")
            s.net shouldBe pln("1000")
            s.fixedTotal shouldBe pln("100")
            s.rest shouldBe pln("900")
            s.shortfall shouldBe Money.ZERO
            s.targets shouldBe mapOf("alice" to pln("450"), "bob" to pln("450"), "carol" to pln("100"))
            s.line("carol")!!.isFixed shouldBe true
            s.line("alice")!!.isFixed shouldBe false
            s.problems.shouldBeEmpty()

            scenario.apply(SettleSuggestions("gig"))
            scenario.settle("gig").status shouldBe EventStatus.SETTLED
        }

        expect("refunds are paid on top of the fixed amount") {
            val scenario = gig()
                .apply(AddCost("c", "carol", pln("30"), DATE, incurredForEventId = "gig"))
                .apply(AddHolding("gig", "h", "alice", pln("1000"), DOOR))
                .apply(SetFixedShare("gig", "carol", pln("100")))
            val s = scenario.settle("gig")
            s.net shouldBe pln("970")
            s.line("carol")!!.share shouldBe pln("100")
            s.line("carol")!!.refunds shouldBe pln("30")
            s.targets shouldBe mapOf("alice" to pln("435"), "bob" to pln("435"), "carol" to pln("130"))
        }

        expect("a shortfall is a loss") {
            val scenario = gig()
                .apply(AddHolding("gig", "h", "alice", pln("150"), DOOR))
                .apply(SetFixedShare("gig", "bob", pln("100")))
                .apply(SetFixedShare("gig", "carol", pln("100")))
            val s = scenario.settle("gig")
            s.status shouldBe EventStatus.LOSS
            s.problems shouldBe setOf(SettlementProblem.LOSS)
            // Income covers the net, but not the fixed amounts: the shortfall is what the fixed amounts exceed it by.
            s.net shouldBe pln("150")
            s.fixedTotal shouldBe pln("200")
            s.rest shouldBe pln("-50")
            s.shortfall shouldBe pln("50")
            s.suggestions.shouldBeEmpty()
            // The fixed amounts stay owed: the targets show what the income would have to cover.
            s.line("carol")!!.target shouldBe pln("100")
            s.line("alice")!!.target shouldBe pln("0")

            scenario.apply(SetFixedShare("gig", "carol", pln("50")))
            scenario.settle("gig").status shouldBe EventStatus.OPEN
        }

        expect("an unclaimed rest is no shares") {
            val scenario = Scenario("Alice", "Bob")
                .apply(
                    CreateEvent(
                        "e",
                        "e",
                        DATE,
                        participants = listOf(Participant("alice", 0), Participant("bob", fixedShare = pln("100"))),
                    ),
                )
                .apply(AddHolding("e", "h", "alice", pln("150"), DOOR))
            scenario.settle("e").problems shouldBe setOf(SettlementProblem.NO_SHARES)

            // Nothing left after the fixed amount: nobody needs a percentage.
            scenario.apply(SetFixedShare("e", "bob", pln("150")))
            val s = scenario.settle("e")
            s.problems.shouldBeEmpty()
            s.suggestions shouldBe listOf(SuggestedTransfer("alice", "bob", pln("150")))
        }

        expect("the rest is rounded among the others in party order") {
            val scenario = Scenario("Alice", "Bob", "Carol", "Dave").event("gig", "Alice", "Bob", "Carol", "Dave")
                .apply(AddHolding("gig", "h", "alice", pln("110.01"), DOOR))
                .apply(SetFixedShare("gig", "alice", pln("10")))
            val s = scenario.settle("gig")
            s.targets shouldBe
                mapOf("alice" to pln("10"), "bob" to pln("33.34"), "carol" to pln("33.34"), "dave" to pln("33.33"))
            s.lines.fold(Money.ZERO) { sum, line -> sum + line.target } shouldBe s.income
        }

        expect("switching back restores the weight") {
            val scenario = gig().apply(SetWeight("gig", "bob", 150)).apply(SetFixedShare("gig", "bob", pln("80")))
            scenario.band.event("gig")!!.participant("bob") shouldBe Participant("bob", 150, addedBy = null, fixedShare = pln("80"))
            scenario.apply(SetFixedShare("gig", "bob", null))
            val bob = scenario.band.event("gig")!!.participant("bob")!!
            bob.weight shouldBe 150
            bob.fixedShare.shouldBeNull()
        }

        expect("switching back with a new weight is one operation and one undo") {
            val scenario = gig().apply(SetFixedShare("gig", "bob", pln("80")))
            val before = scenario.band
            val op = SetFixedShare("gig", "bob", null, weight = 50)
            scenario.apply(op)
            scenario.band.event("gig")!!.participant("bob") shouldBe Participant("bob", 50, addedBy = null)
            // Without a weight the old one stays.
            gig().apply(SetFixedShare("gig", "bob", pln("80"))).apply(SetFixedShare("gig", "bob", null))
                .band.event("gig")!!.participant("bob")!!.weight shouldBe 100

            scenario.apply(Undo("undone", op, BandChange.between(before, scenario.band)))
            scenario.band.event("gig")!!.participant("bob") shouldBe Participant("bob", 100, addedBy = null, fixedShare = pln("80"))
        }

        expect("reducer validates the participant and the amount") {
            val scenario = gig()
            scenario.rejected(SetFixedShare("gig", "nobody", pln("10"))).reason shouldBe RejectionReason.NOT_FOUND
            scenario.rejected(SetFixedShare("gig", "bob", pln("0"))).reason shouldBe RejectionReason.INVALID_AMOUNT
            scenario.rejected(SetFixedShare("gig", "bob", pln("-5"))).reason shouldBe RejectionReason.INVALID_AMOUNT
            // The weight that comes with switching back is checked as by SetWeight.
            scenario.rejected(SetFixedShare("gig", "bob", null, weight = -1)).reason shouldBe RejectionReason.INVALID_SHARE
            scenario.rejected(SetFixedShare("gig", "bob", null, weight = 10_001)).reason shouldBe RejectionReason.INVALID_SHARE
            scenario.rejected(
                CreateEvent("e2", "e2", DATE, participants = listOf(Participant("alice", fixedShare = pln("0")))),
            ).reason shouldBe RejectionReason.INVALID_AMOUNT
            scenario.apply(SetEventArchived("gig", true))
            scenario.rejected(SetFixedShare("gig", "bob", pln("10"))).reason shouldBe RejectionReason.ARCHIVED_EVENT
        }

        expect("amounts are bounded") {
            val scenario = gig()
            scenario.rejected(SetFixedShare("gig", "bob", Money(Limits.MAX_AMOUNT + 1))).reason shouldBe RejectionReason.INVALID_AMOUNT
            scenario.rejected(
                CreateEvent(
                    "e2",
                    "e2",
                    DATE,
                    participants = listOf(Participant("alice", fixedShare = Money(Limits.MAX_AMOUNT + 1))),
                ),
            ).reason shouldBe RejectionReason.INVALID_AMOUNT
            // The largest fixed amounts for every participant still settle without overflow.
            listOf("alice", "bob", "carol").forEach { scenario.apply(SetFixedShare("gig", it, Money(Limits.MAX_AMOUNT))) }
            repeat(3) { scenario.apply(AddHolding("gig", "h$it", "alice", Money(Limits.MAX_AMOUNT), DOOR)) }
            scenario.settle("gig").problems.shouldBeEmpty()
        }

        expect("only leads set fixed shares") {
            val scenario = gig()
            val lead = scenario.userFor("bob", Role.LEAD)
            val member = scenario.userFor("carol", Role.MEMBER)
            fun allowed(actor: Actor, op: Operation) = Permissions.isAllowed(scenario.band, actor, op)
            allowed(lead, SetFixedShare("gig", "carol", pln("10"))) shouldBe true
            allowed(lead, SetFixedShare("gig", "carol", null)) shouldBe true
            withClue("not even for themselves") { allowed(member, SetFixedShare("gig", "carol", pln("10"))) shouldBe false }
        }

        expect("undo puts the percentage back and redo the amount") {
            val scenario = gig()
            val op = SetFixedShare("gig", "carol", pln("100"))
            isUndoable(op) shouldBe true
            val before = scenario.band
            scenario.apply(op)
            val undo = Undo("undone", op, BandChange.between(before, scenario.band))
            val beforeUndo = scenario.band
            scenario.apply(undo)
            scenario.band.event("gig")!!.participant("carol")!!.fixedShare.shouldBeNull()

            scenario.apply(Undo("redone", undo, BandChange.between(beforeUndo, scenario.band)))
            scenario.band.event("gig")!!.participant("carol")!!.fixedShare shouldBe pln("100")
        }

        expect("serializes and old data reads as percentages") {
            listOf(
                SetFixedShare("gig", "carol", pln("100")),
                SetFixedShare("gig", "carol", null),
                SetFixedShare("gig", "carol", null, 50),
            ).forEach { op ->
                val envelope = OpEnvelope("op1", "client", 1, 42, op)
                val json = KapeluszJson.encodeToString(OpEnvelope.serializer(), envelope)
                withClue(json) { json shouldContain "\"type\":\"SetFixedShare\"" }
                KapeluszJson.decodeFromString(OpEnvelope.serializer(), json) shouldBe envelope
            }
            val band = gig().apply(SetFixedShare("gig", "carol", pln("100"))).band
            val json = KapeluszJson.encodeToString(Band.serializer(), band)
            withClue(json) { json shouldContain "\"fixedShare\":10000" }
            KapeluszJson.decodeFromString(Band.serializer(), json) shouldBe band
            KapeluszJson.decodeFromString(Participant.serializer(), """{"partyId":"bob"}""") shouldBe Participant("bob")
            // Operations from before the weight read without it.
            KapeluszJson.decodeFromString(
                Operation.serializer(),
                """{"type":"SetFixedShare","eventId":"gig","partyId":"carol","amount":null}""",
            ) shouldBe SetFixedShare("gig", "carol", null)
        }
    })

private fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob", "Carol")
