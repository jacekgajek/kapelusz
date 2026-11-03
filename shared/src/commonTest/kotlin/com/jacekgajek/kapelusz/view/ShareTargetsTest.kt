package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.view.BandView
import com.jacekgajek.kapelusz.view.ShareTarget
import com.jacekgajek.kapelusz.view.ShareTargets
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
    when (val result = Engine.apply(band, Actor.System, op, "op", 1)) {
        is ApplyResult.Applied -> result.band
        is ApplyResult.Rejected -> error("$op: ${result.rejection}")
    }
}

class ShareTargetsTest :
    ExpectSpec({
        val today = LocalDate(2026, 10, 25)
        val lineup = listOf(Participant("owner"), Participant("lead"), Participant("member"))

        val band = Band.create("b", "Night Owls", "PLN", "owner", "Owner", "u-owner").apply(
            AddMember("lead", "Lead"), LinkUser("lead", "u-lead", Role.LEAD),
            AddMember("member", "Member"), LinkUser("member", "u-member", Role.MEMBER),
            AddMember("viewer", "Viewer"), LinkUser("viewer", "u-viewer", Role.VIEWER),
            CreateEvent("old", "Old gig", LocalDate(2026, 9, 1), participants = lineup),
            CreateEvent("today", "Today's gig", today, participants = lineup),
            CreateEvent("soon", "Next gig", LocalDate(2026, 11, 1), participants = lineup),
            CreateEvent("later", "Later gig", LocalDate(2026, 12, 1), participants = lineup),
            CreateEvent("archived", "Archived gig", LocalDate(2026, 10, 1), participants = lineup),
            SetEventArchived("archived", true),
        )

        expect("past events come first newest first then upcoming soonest first") {
            val events = ShareTargets.events(listOf(band), "u-member", today)
            withClue("archived events are left out") { events.map { it.event.id } shouldBe listOf("today", "old", "soon", "later") }
            events.map { it.upcoming } shouldBe listOf(false, false, true, true)
        }

        expect("events of every band") {
            val other = Band.create("b2", "Jazz Trio", "PLN", "m", "Member", "u-member")
                .apply(CreateEvent("jazz", "Jazz night", LocalDate(2026, 10, 20), participants = listOf(Participant("m"))))
            val events = ShareTargets.events(listOf(band, other), "u-member", today)
            events.map { it.event.id } shouldBe listOf("today", "jazz", "old", "soon", "later")
        }

        expect("viewers can attach nothing") {
            ShareTargets.events(listOf(band), "u-viewer", today).shouldBeEmpty()
        }

        expect("a member gets new entries and their own costs, a leader the event's files too") {
            val withEntries = band.apply(
                AddCost("mine", "member", Money(1000), today, incurredForEventId = "today", description = "Strings"),
                AddCost("theirs", "lead", Money(2000), today, incurredForEventId = "today"),
                AddHolding("today", "door", "member", Money(5000), IncomeSource.DOOR),
            )
            val event = withEntries.event("today")!!
            val member = ShareTargets.targets(BandView(withEntries), "u-member", event)
            member.map { it::class } shouldBe
                listOf(
                    ShareTarget.NewCost::class,
                    ShareTarget.NewCollected::class,
                    ShareTarget.ExistingCost::class,
                    ShareTarget.ExistingCollected::class,
                )
            (member[2] as ShareTarget.ExistingCost).cost.id shouldBe "mine"

            val lead = ShareTargets.targets(BandView(withEntries), "u-lead", event)
            withClue("leaders attach the event's own files") { (lead.any { it is ShareTarget.EventFiles }) shouldBe true }
            lead.filterIsInstance<ShareTarget.ExistingCost>().map { it.cost.id }.toSet() shouldBe setOf("mine", "theirs")
        }

        expect("a cost paid back in full is left out") {
            val refunded = band.apply(
                SetTreasurer("owner"),
                AddCost("paid", "member", Money(1000), today, incurredForEventId = "today"),
                // Not out of this event's money: out of the band fund.
                SetRefund("today", "paid", Money.ZERO),
                AddFundEntry("refund", today, "Strings", Money(-1000), costId = "paid"),
            )
            val targets = ShareTargets.targets(BandView(refunded), "u-lead", refunded.event("today")!!)
            targets.none { it is ShareTarget.ExistingCost } shouldBe true
        }

        expect("room is what the limit leaves") {
            val withFiles = band.apply(
                AddCost("c", "member", Money(1000), today, incurredForEventId = "today"),
                AddAttachment("c", Attachment("a1", AttachmentKind.IMAGE, "a1.jpg", 100)),
                AddAttachment("c", Attachment("a2", AttachmentKind.IMAGE, "a2.jpg", 100)),
            )
            val cost = ShareTargets.targets(BandView(withFiles), "u-member", withFiles.event("today")!!)
                .filterIsInstance<ShareTarget.ExistingCost>()
                .single()
            cost.room shouldBe 3
        }
    })
