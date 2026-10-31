package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.model.ShareTarget
import com.jacekgajek.kapelusz.app.ui.model.ShareTargets
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.rules.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class ShareTargetsTest {
    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        when (val result = Engine.apply(band, Actor.System, op, "op", 1)) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> error("$op: ${result.rejection}")
        }
    }

    private val today = LocalDate(2026, 10, 25)
    private val lineup = listOf(Participant("owner"), Participant("lead"), Participant("member"))

    private val band = Band.create("b", "Night Owls", "PLN", "owner", "Owner", "u-owner").apply(
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

    @Test
    fun pastEventsComeFirstNewestFirstThenUpcomingSoonestFirst() {
        val events = ShareTargets.events(listOf(band), "u-member", today)
        assertEquals(listOf("today", "old", "soon", "later"), events.map { it.event.id }, "archived events are left out")
        assertEquals(listOf(false, false, true, true), events.map { it.upcoming })
    }

    @Test
    fun eventsOfEveryBand() {
        val other = Band.create("b2", "Jazz Trio", "PLN", "m", "Member", "u-member")
            .apply(CreateEvent("jazz", "Jazz night", LocalDate(2026, 10, 20), participants = listOf(Participant("m"))))
        val events = ShareTargets.events(listOf(band, other), "u-member", today)
        assertEquals(listOf("today", "jazz", "old", "soon", "later"), events.map { it.event.id })
    }

    @Test
    fun viewersCanAttachNothing() {
        assertEquals(emptyList(), ShareTargets.events(listOf(band), "u-viewer", today))
    }

    @Test
    fun aMemberGetsNewEntriesAndTheirOwnCostsALeaderTheEventsFilesToo() {
        val withEntries = band.apply(
            AddCost("mine", "member", Money(1000), today, incurredForEventId = "today", description = "Strings"),
            AddCost("theirs", "lead", Money(2000), today, incurredForEventId = "today"),
            AddHolding("today", "door", "member", Money(5000), IncomeSource.DOOR),
        )
        val event = withEntries.event("today")!!
        val member = ShareTargets.targets(withEntries, "u-member", event)
        assertEquals(
            listOf(ShareTarget.NewCost::class, ShareTarget.NewCollected::class, ShareTarget.ExistingCost::class, ShareTarget.ExistingCollected::class),
            member.map { it::class },
        )
        assertEquals("mine", (member[2] as ShareTarget.ExistingCost).cost.id)

        val lead = ShareTargets.targets(withEntries, "u-lead", event)
        assertTrue(lead.any { it is ShareTarget.EventFiles }, "leaders attach the event's own files")
        assertEquals(setOf("mine", "theirs"), lead.filterIsInstance<ShareTarget.ExistingCost>().map { it.cost.id }.toSet())
    }

    @Test
    fun aCostPaidBackInFullIsLeftOut() {
        val refunded = band.apply(
            SetTreasurer("owner"),
            AddCost("paid", "member", Money(1000), today, incurredForEventId = "today"),
            // Not out of this event's money: out of the band fund.
            SetRefund("today", "paid", Money.ZERO),
            AddFundEntry("refund", today, "Strings", Money(-1000), costId = "paid"),
        )
        val targets = ShareTargets.targets(refunded, "u-lead", refunded.event("today")!!)
        assertTrue(targets.none { it is ShareTarget.ExistingCost })
    }

    @Test
    fun roomIsWhatTheLimitLeaves() {
        val withFiles = band.apply(
            AddCost("c", "member", Money(1000), today, incurredForEventId = "today"),
            AddAttachment("c", Attachment("a1", AttachmentKind.IMAGE, "a1.jpg", 100)),
            AddAttachment("c", Attachment("a2", AttachmentKind.IMAGE, "a2.jpg", 100)),
        )
        val cost = ShareTargets.targets(withFiles, "u-member", withFiles.event("today")!!).filterIsInstance<ShareTarget.ExistingCost>().single()
        assertEquals(3, cost.room)
    }
}
