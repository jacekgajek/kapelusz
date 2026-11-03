package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.DATE
import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.rules.Permissions
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

class PermissionsTest :
    ExpectSpec({

        // The scenario is mutable and the tests change it: every test gets a fresh instance of the spec.
        isolationMode = IsolationMode.InstancePerRoot

        val scenario = Scenario("Owner", "Admin", "Lead", "Member", "Viewer", "Other")
            .event("gig", "Owner", "Lead", "Member", "Other")
            .apply(AddHolding("gig", "h-other", "other", "100".let(::pln), DOOR))

        val owner = Actor.User("user-owner")
        val admin = scenario.userFor("admin", Role.LEAD)
        val lead = scenario.userFor("lead", Role.LEAD)
        val member = scenario.userFor("member", Role.MEMBER)
        val viewer = scenario.userFor("viewer", Role.VIEWER)

        fun allowed(actor: Actor, op: Operation) = Permissions.isAllowed(scenario.band, actor, op)

        expect("viewer is read-only") {
            allowed(viewer, AddCost("c", "viewer", pln("1"), DATE)) shouldBe false
            allowed(viewer, RecordTransfer("gig", "t", "viewer", "member", pln("1"))) shouldBe false
        }

        expect("member acts only for themselves") {
            allowed(member, AddCost("c", "member", pln("1"), DATE)) shouldBe true
            allowed(member, AddCost("c", "other", pln("1"), DATE)) shouldBe false
            allowed(member, AddHolding("gig", "h", "member", pln("1"), DOOR)) shouldBe true
            allowed(member, AddHolding("gig", "h", "other", pln("1"), DOOR)) shouldBe false
            allowed(member, RecordTransfer("gig", "t", "other", "member", pln("1"))) shouldBe true
            allowed(member, RecordTransfer("gig", "t", "other", "lead", pln("1"))) shouldBe false
            allowed(member, SettleSuggestions("gig", "member")) shouldBe true
            allowed(member, SettleSuggestions("gig")) shouldBe false
            allowed(member, CreateEvent("e2", "e2", DATE)) shouldBe false
            allowed(member, SetRefund("gig", "c", pln("1"))) shouldBe false
        }

        expect("member holding requires participation") {
            val outsider = scenario.userFor("viewer", Role.VIEWER)
            scenario.apply(SetMemberRole("viewer", Role.MEMBER))
            allowed(outsider, AddHolding("gig", "h", "viewer", pln("1"), DOOR)) shouldBe false
        }

        expect("own cost locks once refunded in event with transfers") {
            scenario.apply(AddCost("c", "member", pln("10"), DATE, incurredForEventId = "gig"))
            val edit = UpdateCost("c", amount = Change(pln("12")))
            allowed(member, edit) shouldBe true

            scenario.apply(RecordTransfer("gig", "t", "other", "member", pln("10")))
            allowed(member, edit) shouldBe false
            allowed(lead, edit) shouldBe true
        }

        expect("leader runs events and the roster") {
            // One role since the Leader role was merged into it.
            allowed(lead, CreateEvent("e2", "e2", DATE)) shouldBe true
            allowed(lead, SettleSuggestions("gig")) shouldBe true
            allowed(lead, AddHolding("gig", "h", "other", pln("1"), DOOR)) shouldBe true
            allowed(lead, AddMember("x", "X")) shouldBe true
            allowed(lead, SetMemberRole("member", Role.LEAD)) shouldBe true
            allowed(member, AddMember("x", "X")) shouldBe false
        }

        expect("leaders manage the roster but only the owner transfers ownership") {
            allowed(admin, AddMember("x", "X")) shouldBe true
            allowed(admin, SetMemberRole("member", Role.LEAD)) shouldBe true
            allowed(admin, TransferOwnership("admin")) shouldBe false
            allowed(owner, TransferOwnership("admin")) shouldBe true
        }

        expect("outsiders, archived members and linking are forbidden") {
            val stranger = Permissions.check(scenario.band, Actor.User("stranger"), AddCost("c", "member", pln("1"), DATE))!!
            stranger.code shouldBe RejectionCode.FORBIDDEN
            stranger.reason shouldBe RejectionReason.NOT_A_MEMBER
            Permissions.check(scenario.band, member, CreateEvent("e2", "e2", DATE))!!.reason shouldBe RejectionReason.ROLE_REQUIRED
            allowed(owner, LinkUser("other", "someone", Role.MEMBER)) shouldBe false
            allowed(Actor.System, LinkUser("other", "someone", Role.MEMBER)) shouldBe true

            scenario.apply(SetMemberArchived("member", true))
            allowed(member, AddCost("c", "member", pln("1"), DATE)) shouldBe false
        }

        expect("treasurer acts for band fund") {
            val toFund = RecordTransfer("gig", "t", "other", BAND_FUND, pln("1"))
            allowed(member, toFund) shouldBe false
            allowed(member, SettleSuggestions("gig", BAND_FUND)) shouldBe false

            scenario.apply(SetTreasurer("member"))
            allowed(member, toFund) shouldBe true
            allowed(member, RecordTransfer("gig", "t", BAND_FUND, "other", pln("1"))) shouldBe true
            allowed(member, SettleSuggestions("gig", BAND_FUND)) shouldBe true
            allowed(member, SettleSuggestions("gig")) shouldBe false
        }

        expect("who recorded a transfer removes it") {
            scenario.apply(RecordTransfer("gig", "mine", "other", "member", pln("10")), member)
            scenario.apply(RecordTransfer("gig", "theirs", "other", "member", pln("10")))
            withClue("a Paid tapped by mistake") { allowed(member, RemoveTransfer("gig", "mine")) shouldBe true }
            allowed(member, RemoveTransfer("gig", "theirs")) shouldBe false
            allowed(lead, RemoveTransfer("gig", "mine")) shouldBe true
        }

        expect("the treasurer keeps the fund's ledger") {
            val entry = AddFundEntry("e", DATE, "Membership fee", pln("40"), paidBy = "other")
            allowed(member, entry) shouldBe false
            scenario.apply(SetTreasurer("member"))
            allowed(member, entry) shouldBe true
            scenario.apply(entry, member)
            allowed(member, UpdateFundEntry("e", amount = Change(pln("45")))) shouldBe true
            allowed(member, RemoveFundEntry("e")) shouldBe true
            // Refunding a cost decides about it: leads only.
            scenario.apply(AddCost("c", "other", pln("10"), DATE))
            allowed(member, AddFundEntry("r", DATE, "Refund", pln("-10"), costId = "c")) shouldBe false
            scenario.apply(AddFundEntry("r", DATE, "Refund", pln("-10"), costId = "c"))
            allowed(member, RemoveFundEntry("r")) shouldBe false
            allowed(lead, RemoveFundEntry("r")) shouldBe true
        }
    })
