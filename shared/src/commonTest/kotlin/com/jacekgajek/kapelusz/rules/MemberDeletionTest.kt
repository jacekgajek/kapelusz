package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.MemberDeletion
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Deleting a member added by mistake: only who left no trace in the band. */
class MemberDeletionTest :
    ExpectSpec({

        fun band() = Scenario("Alice", "Bob", "Carol", "Dan", "Erin")

        expect("a member who did nothing is deleted") {
            val scenario = band().event("gig", "Alice", "Bob")
            val alice = scenario.userFor("alice", Role.OWNER)
            MemberDeletion.possible(scenario.band, "carol") shouldBe true
            scenario.apply(DeleteMember("carol"), alice)
            scenario.band.member("carol").shouldBeNull()
        }

        expect("anything referring to the member keeps them") {
            val scenario = band().event("gig", "Alice", "Bob")
            scenario.apply(AddCost("c1", "carol", pln("50"), DATE))
            scenario.apply(SetTreasurer("dan"))
            // Settling together is between people in the app.
            scenario.userFor("alice", Role.OWNER)
            scenario.userFor("erin", Role.MEMBER)
            scenario.apply(InviteToSettleTogether("erin", "alice"))
            withClue("took part") { MemberDeletion.possible(scenario.band, "bob") shouldBe false }
            withClue("has a cost") { MemberDeletion.possible(scenario.band, "carol") shouldBe false }
            withClue("holds the band fund") { MemberDeletion.possible(scenario.band, "dan") shouldBe false }
            withClue("asked to settle together") { MemberDeletion.possible(scenario.band, "erin") shouldBe false }
            withClue("the owner, and asked to settle together") { MemberDeletion.possible(scenario.band, "alice") shouldBe false }
            scenario.rejected(DeleteMember("bob")).reason shouldBe RejectionReason.MEMBER_HAS_HISTORY
            scenario.rejected(DeleteMember("carol")).reason shouldBe RejectionReason.MEMBER_HAS_HISTORY
        }

        expect("a leader who added someone without playing stays") {
            val scenario = band()
            val bob = scenario.userFor("bob", Role.LEAD)
            scenario.apply(CreateEvent("gig", "gig", DATE, participants = listOf(Participant("alice"))), bob)
            scenario.apply(UnlinkUser("bob"))
            withClue("named as who added Alice") { MemberDeletion.possible(scenario.band, "bob") shouldBe false }
        }

        expect("a linked member is unlinked first") {
            val scenario = band()
            val alice = scenario.userFor("alice", Role.OWNER)
            scenario.userFor("carol", Role.MEMBER)
            scenario.rejected(DeleteMember("carol"), alice).reason shouldBe RejectionReason.MEMBER_LINKED
            scenario.apply(UnlinkUser("carol"), alice).apply(DeleteMember("carol"), alice)
            scenario.band.member("carol").shouldBeNull()
        }

        expect("a leader who recorded money without playing stays") {
            val scenario = band().event("gig", "Alice", "Carol")
            val bob = scenario.userFor("bob", Role.LEAD)
            scenario.apply(AddHolding("gig", "h1", "carol", pln("100"), IncomeSource.HAT), bob)
            scenario.apply(UnlinkUser("bob"))
            withClue("named as who recorded Carol's money") { MemberDeletion.possible(scenario.band, "bob") shouldBe false }
        }

        expect("undoing a deletion whose name was taken since is refused") {
            val scenario = band()
            val before = scenario.band
            scenario.apply(DeleteMember("carol"))
            val undo = Undo("deleted", DeleteMember("carol"), BandChange.between(before, scenario.band))
            scenario.apply(AddMember("carol2", "Carol"))
            withClue("two members named Carol") { scenario.rejected(undo).reason shouldBe RejectionReason.CHANGED_SINCE }
            scenario.apply(RenameMember("carol2", "Caroline"))
            scenario.apply(undo)
            scenario.band.member("carol")?.name shouldBe "Carol"
        }

        expect("who paid into the fund stays") {
            val scenario = band().apply(SetTreasurer("alice"))
            scenario.apply(AddFundEntry("fee", DATE, "Membership fee", pln("50"), paidBy = "carol"))
            withClue("paid into the fund") { MemberDeletion.possible(scenario.band, "carol") shouldBe false }
        }
    })
