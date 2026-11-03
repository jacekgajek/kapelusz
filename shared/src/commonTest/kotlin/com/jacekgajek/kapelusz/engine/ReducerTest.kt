package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ReducerTest :
    ExpectSpec({

        fun gig() = Scenario("Alice", "Bob", "Carol").event("gig", "Alice", "Bob")

        expect("cost incurred for event is pre-included only for participants") {
            val scenario = gig()
                .apply(AddCost("bob-cost", "bob", pln("40"), DATE, incurredForEventId = "gig"))
            scenario.band.event("gig")!!.refunds shouldBe listOf(Refund("bob-cost", pln("40")))
            // Only for an event its owner takes part in.
            scenario.rejected(AddCost("carol-cost", "carol", pln("25"), DATE, incurredForEventId = "gig")).reason shouldBe
                RejectionReason.NOT_A_PARTICIPANT

            scenario.apply(AddParticipant("gig", "carol"))
            scenario.apply(AddCost("carol-cost", "carol", pln("25"), DATE, incurredForEventId = "gig"))
            scenario.band.event("gig")!!.refundOf("carol-cost")!!.amount shouldBe pln("25")
        }

        expect("editing cost amount keeps full refund at own event") {
            val scenario = gig()
                .apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"))
                .apply(UpdateCost("c", amount = Change(pln("55"))))
            scenario.band.event("gig")!!.refundOf("c")!!.amount shouldBe pln("55")
        }

        expect("cost cannot drop below refunded") {
            val scenario = gig()
                .apply(AddCost("c", "bob", pln("40"), DATE))
                .apply(SetRefund("gig", "c", pln("30")))
            scenario.rejected(UpdateCost("c", amount = Change(pln("20")))).code shouldBe RejectionCode.INVALID
        }

        expect("refund rules") {
            val scenario = gig().apply(AddCost("c", "carol", pln("40"), DATE)).apply(AddCost("b", "bob", pln("40"), DATE))
            withClue("non-participant") { scenario.rejected(SetRefund("gig", "c", pln("10"))).code shouldBe RejectionCode.INVALID }
            withClue("exceeds") { scenario.rejected(SetRefund("gig", "b", pln("41"))).code shouldBe RejectionCode.INVALID }
            scenario.apply(SetRefund("gig", "b", pln("40"))).apply(SetRefund("gig", "b", Money.ZERO))
            scenario.band.event("gig")!!.refunds.shouldBeEmpty()
        }

        expect("deleting cost removes its refunds") {
            val scenario = gig().apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig")).apply(DeleteCost("c"))
            scenario.band.event("gig")!!.refunds.shouldBeEmpty()
        }

        expect("event with transfers cannot be deleted") {
            val scenario = gig()
                .apply(AddHolding("gig", "h", "alice", pln("100"), DOOR))
                .apply(RecordTransfer("gig", "t", "alice", "bob", pln("50")))
            scenario.rejected(DeleteEvent("gig")).code shouldBe RejectionCode.CONFLICT
        }

        expect("deleting event detaches its costs") {
            val scenario = gig().apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig")).apply(DeleteEvent("gig"))
            scenario.band.cost("c")!!.eventId.shouldBeNull()
        }

        expect("participant with entries cannot be removed") {
            val scenario = gig().apply(AddHolding("gig", "h", "bob", pln("100"), DOOR))
            val rejection = scenario.rejected(RemoveParticipant("gig", "bob"))
            rejection.code shouldBe RejectionCode.CONFLICT
            rejection.reason shouldBe RejectionReason.PARTICIPANT_HAS_MONEY
            scenario.apply(RemoveParticipant("gig", "alice"))
            scenario.band.event("gig")!!.participant("alice").shouldBeNull()
        }

        expect("fund requires treasurer") {
            gig().rejected(SetFundAmount("gig", pln("10"))).code shouldBe RejectionCode.INVALID
        }

        expect("member with history cannot be deleted") {
            val scenario = gig()
            scenario.rejected(DeleteMember("bob")).code shouldBe RejectionCode.CONFLICT
            scenario.apply(DeleteMember("carol"))
            scenario.band.member("carol").shouldBeNull()
        }

        expect("ownership transfer") {
            val scenario = gig()
            scenario.userFor("bob", Role.LEAD)
            scenario.rejected(SetMemberRole("alice", Role.LEAD)).code shouldBe RejectionCode.INVALID
            scenario.apply(TransferOwnership("bob"))
            scenario.band.member("bob")!!.role shouldBe Role.OWNER
            scenario.band.member("alice")!!.role shouldBe Role.LEAD
        }

        expect("event updates are patches and stamp updatedAt") {
            val scenario = gig().apply(UpdateEvent("gig", location = Change("Hall")))
            val before = scenario.band.event("gig")!!
            scenario.apply(UpdateEvent("gig", notes = Change("bring cables")))
            val after = scenario.band.event("gig")!!
            after.location shouldBe "Hall"
            after.notes shouldBe "bring cables"
            after.updatedAt shouldBeGreaterThan before.updatedAt
        }

        expect("batch is idempotent and rejections advance sequence") {
            val band = gig().band
            val owner = Actor.User("user-alice")
            val batch = listOf(
                OpEnvelope("a", "phone", 1, 1, RenameBand("New name")),
                OpEnvelope("b", "phone", 2, 2, DeleteEvent("missing")),
                OpEnvelope("c", "phone", 3, 3, AddCost("c", "bob", pln("10"), DATE)),
            )
            val first = Engine.applyBatch(band, owner, batch)
            first.accepted shouldBe listOf("a", "c")
            first.rejected.getValue("b").code shouldBe RejectionCode.NOT_FOUND
            first.band.lastSeq["phone"] shouldBe 3L

            val retry = Engine.applyBatch(first.band, owner, batch + OpEnvelope("d", "phone", 4, 4, RenameBand("Newer")))
            retry.duplicates shouldBe listOf("a", "c")
            withClue("a retried refusal is reported again") { retry.rejected.getValue("b").code shouldBe RejectionCode.NOT_FOUND }
            retry.accepted shouldBe listOf("d")
            retry.band.costs.size shouldBe 1
            retry.band.name shouldBe "Newer"
        }

        expect("guest id cannot shadow member") {
            val op = CreateEvent("x", "x", DATE, guests = listOf(Guest("bob", "Fake")), participants = listOf(Participant("bob")))
            gig().rejected(op).code shouldBe RejectionCode.INVALID
        }

        expect("moving cost to another event pre-includes it there") {
            val scenario = gig().event("other-gig", "Bob")
                .apply(AddCost("c", "bob", pln("40"), DATE))
                .apply(UpdateCost("c", incurredForEventId = Change("other-gig")))
            scenario.band.event("other-gig")!!.refundOf("c")!!.amount shouldBe pln("40")
            scenario.band.event("gig")!!.refundOf("c").shouldBeNull()
        }

        expect("cost changes stamp the events they refund") {
            val scenario = gig()
            val created = scenario.band.event("gig")!!.updatedAt
            scenario.apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"))
            val afterAdd = scenario.band.event("gig")!!.updatedAt
            afterAdd shouldBeGreaterThan created
            scenario.apply(DeleteCost("c"))
            scenario.band.event("gig")!!.updatedAt shouldBeGreaterThan afterAdd
        }

        expect("a member's cost does not reopen a settled or archived event") {
            val scenario = gig().apply(
                com.jacekgajek.kapelusz.ops.AddHolding(
                    "gig",
                    "h",
                    "alice",
                    pln("100"),
                    com.jacekgajek.kapelusz.model.IncomeSource.HAT,
                    null,
                ),
            )
                .apply(com.jacekgajek.kapelusz.ops.SettleSuggestions("gig"))
            scenario.apply(AddCost("late", "bob", pln("30"), DATE, incurredForEventId = "gig"))
            withClue("money moved: a lead decides") { scenario.band.event("gig")!!.refundOf("late").shouldBeNull() }
            scenario.apply(com.jacekgajek.kapelusz.ops.SetEventArchived("gig", true))
            scenario.apply(AddCost("later", "bob", pln("20"), DATE))
            // Archived events are read-only: not even a cost can be assigned to one.
            scenario.rejected(UpdateCost("later", incurredForEventId = Change("gig"))).reason shouldBe
                com.jacekgajek.kapelusz.model.RejectionReason.ARCHIVED_EVENT
        }

        expect("a member's amount change does not raise the refund of a settled event") {
            val scenario = gig().apply(AddCost("c", "bob", pln("40"), DATE, incurredForEventId = "gig"))
                .apply(
                    com.jacekgajek.kapelusz.ops.AddHolding(
                        "gig",
                        "h",
                        "alice",
                        pln("100"),
                        com.jacekgajek.kapelusz.model.IncomeSource.HAT,
                        null,
                    ),
                )
                .apply(com.jacekgajek.kapelusz.ops.SettleSuggestions("gig"))
            scenario.apply(UpdateCost("c", amount = Change(pln("60"))))
            withClue("settled: the refund stays; a lead decides") {
                scenario.band.event("gig")!!.refundOf("c")!!.amount shouldBe pln("40")
            }
        }
    })
