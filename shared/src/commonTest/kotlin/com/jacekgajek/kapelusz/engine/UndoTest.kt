package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.engine.account
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Applies [op] like the server does and returns its undo, built from what it changed. */
private fun Scenario.recorded(op: Operation, actor: Actor = Actor.System): Undo {
    val before = band
    apply(op, actor)
    return Undo("undone", op, BandChange.between(before, band))
}

class UndoTest :
    ExpectSpec({

        expect("undo puts back what the change touched and undoing the undo redoes it") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice")
            val undoRename = scenario.recorded(UpdateEvent("gig", name = Change("Big gig"), location = Change("Klub")))
            scenario.apply(RenameBand("Other name"))
            val redo = scenario.recorded(undoRename)
            scenario.band.event("gig")!!.name shouldBe "gig"
            scenario.band.event("gig")!!.location.shouldBeNull()
            withClue("later, unrelated changes stay") { scenario.band.name shouldBe "Other name" }
            scenario.apply(redo)
            withClue("undoing the undo redoes") { scenario.band.event("gig")!!.name shouldBe "Big gig" }
            scenario.band.event("gig")!!.location shouldBe "Klub"
        }

        expect("refused when what it touched changed since") {
            val scenario = Scenario("Alice").event("gig", "Alice")
            val undoRename = scenario.recorded(UpdateEvent("gig", name = Change("Big gig")))
            scenario.apply(UpdateEvent("gig", location = Change("Klub")))
            scenario.rejected(undoRename).reason shouldBe RejectionReason.CHANGED_SINCE
        }

        expect("refused when later changes rely on it") {
            val scenario = Scenario("Alice").event("gig", "Alice")
            val undoAddMember = scenario.recorded(com.jacekgajek.kapelusz.ops.AddMember("bob", "Bob"))
            scenario.apply(AddParticipant("gig", "bob"))
            withClue("Bob takes part in the gig now") { scenario.rejected(undoAddMember).reason shouldBe RejectionReason.CHANGED_SINCE }
        }

        expect("refused when it would refund a cost twice") {
            val scenario = Scenario("Alice").event("a", "Alice").event("b", "Alice")
                .apply(AddCost("c", "alice", pln("100"), DATE))
                .apply(SetRefund("a", "c", pln("100")))
            val undoDefer = scenario.recorded(SetRefund("a", "c", pln("0")))
            scenario.apply(SetRefund("b", "c", pln("100")))
            withClue("the cost is refunded at b now") { scenario.rejected(undoDefer).reason shouldBe RejectionReason.CHANGED_SINCE }
        }

        expect("a deleted event comes back in its place") {
            val scenario = Scenario("Alice").event("a", "Alice").event("b", "Alice").event("c", "Alice")
            val undoDelete = scenario.recorded(DeleteEvent("b"))
            scenario.apply(undoDelete)
            scenario.band.events.map { it.id } shouldBe listOf("a", "b", "c")
        }

        expect("membership and ownership cannot be undone") {
            val scenario = Scenario("Alice", "Bob")
            scenario.apply(LinkUser("bob", "user-bob", Role.LEAD))
            val undoTransfer = scenario.recorded(TransferOwnership("bob"))
            scenario.rejected(undoTransfer).reason shouldBe RejectionReason.NOT_UNDOABLE
            withClue("nothing recorded") {
                scenario.rejected(Undo("x", RenameBand("x"), BandChange(BandPart(), BandPart()))).reason shouldBe
                    RejectionReason.NOT_UNDOABLE
            }
        }

        expect("only those who may make the change may undo it") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice")
            scenario.apply(LinkUser("bob", "user-bob", Role.MEMBER))
            val undoRename = scenario.recorded(UpdateEvent("gig", name = Change("Big gig")))
            scenario.rejected(undoRename, Actor.User("user-bob")).reason shouldBe RejectionReason.ROLE_REQUIRED
            val undoBobsCost = scenario.recorded(AddCost("c", "bob", pln("10"), DATE), Actor.User("user-bob"))
            scenario.apply(undoBobsCost, Actor.User("user-bob"))
            scenario.band.cost("c").shouldBeNull()
        }

        expect("the engine records what each operation changed") {
            val scenario = Scenario("Alice").event("gig", "Alice")
            val result = Engine.applyBatch(
                scenario.band,
                Actor.System,
                listOf(
                    OpEnvelope("o1", "phone", 1, 1, UpdateEvent("gig", name = Change("X"))),
                    OpEnvelope("o2", "phone", 2, 2, RenameBand("Y")),
                ),
            )
            result.changes.getValue("o1").after.events.keys shouldBe setOf("gig")
            result.changes.getValue("o2").after.settings?.name shouldBe "Y"
            result.changes.getValue("o2").after.events.shouldBeEmpty()
        }

        expect("the fund stays consistent") {
            val scenario = Scenario("Alice").event("gig", "Alice").apply(com.jacekgajek.kapelusz.ops.SetTreasurer("alice"))
                .apply(com.jacekgajek.kapelusz.ops.SetFundAmount("gig", pln("100")))
            val undoZero = scenario.recorded(com.jacekgajek.kapelusz.ops.SetFundAmount("gig", pln("0")))
            scenario.apply(com.jacekgajek.kapelusz.ops.DisableBandFund)
            withClue("the fund is off now") { scenario.rejected(undoZero).reason shouldBe RejectionReason.CHANGED_SINCE }
        }

        expect("the treasurer stays active") {
            val scenario = Scenario("Alice", "Bob", "Carol").apply(com.jacekgajek.kapelusz.ops.SetTreasurer("carol"))
            val undoBob = scenario.recorded(com.jacekgajek.kapelusz.ops.SetTreasurer("bob"))
            scenario.apply(com.jacekgajek.kapelusz.ops.SetMemberArchived("carol", true))
            withClue("Carol is archived") { scenario.rejected(undoBob).reason shouldBe RejectionReason.CHANGED_SINCE }
        }

        expect("only leads undo transfers") {
            val scenario = Scenario("Alice", "Bob").event("gig", "Alice", "Bob")
            scenario.apply(LinkUser("bob", "user-bob", Role.MEMBER))
            val undoTransfer = scenario.recorded(com.jacekgajek.kapelusz.ops.RecordTransfer("gig", "t", "bob", "alice", pln("10")))
            withClue("removing a transfer needs a lead") {
                scenario.rejected(undoTransfer, Actor.User("user-bob")).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
        }

        expect("a deleted member comes back") {
            val scenario = Scenario("Alice", "Bob", "Carol", "Dave")
            val undoDelete = scenario.recorded(com.jacekgajek.kapelusz.ops.DeleteMember("carol"))
            scenario.apply(undoDelete)
            scenario.band.members.map { it.id } shouldBe listOf("alice", "bob", "carol", "dave")
        }

        expect("photos are not changes for undo") {
            val scenario = Scenario("Alice", "Bob")
            scenario.apply(LinkUser("bob", "user-bob", Role.MEMBER))
            val undoRename = scenario.recorded(com.jacekgajek.kapelusz.ops.RenameMember("bob", "Robert"))
            // The server sets Bob's photo at his next sign-in (not an operation).
            val withPhoto = scenario.band.copy(
                members = scenario.band.members.map {
                    if (it.id == "bob") it.copy(pictureUrl = "https://lh3.googleusercontent.com/b") else it
                },
            )
            val undone = (
                Engine.apply(withPhoto, Actor.System, undoRename, "u", 9) as com.jacekgajek.kapelusz.engine.ApplyResult.Applied
                ).band
            undone.member("bob")!!.name shouldBe "Bob"
            withClue("the photo stays") { undone.member("bob")!!.pictureUrl shouldBe "https://lh3.googleusercontent.com/b" }
            val unlinked = (
                Engine.apply(undone, Actor.System, com.jacekgajek.kapelusz.ops.UnlinkUser("bob"), "x", 10) as
                    com.jacekgajek.kapelusz.engine.ApplyResult.Applied
                ).band
            withClue("the photo goes with the account") { unlinked.member("bob")!!.pictureUrl.shouldBeNull() }
        }

        expect("undos nest only a few levels") {
            var nested: com.jacekgajek.kapelusz.ops.Operation = RenameBand("x")
            repeat(5) { nested = Undo("u$it", nested, BandChange(BandPart(), BandPart())) }
            Scenario("Alice").rejected(nested as Undo).reason shouldBe RejectionReason.NOT_UNDOABLE
        }
    })
