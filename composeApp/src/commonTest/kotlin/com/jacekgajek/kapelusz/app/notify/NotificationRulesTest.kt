package com.jacekgajek.kapelusz.app.notify

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetTreasurer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationRulesTest {
    private var opCount = 0

    // Ops applied as the given user, so recordedBy and addedBy are set as in the app.
    private fun Band.apply(user: String, vararg ops: Operation): Band = ops.fold(this) { band, op ->
        val actor = if (user == "system") Actor.System else Actor.User(user)
        (Engine.apply(band, actor, op, "op${opCount++}", 1) as ApplyResult.Applied).band
    }

    private val base = Band.create("b", "Band", "PLN", "alice", "Alice", "u-alice")
        .apply("system", AddMember("bob", "Bob"), LinkUser("bob", "u-bob", Role.ADMIN))
    private val now = LocalDateTime(2026, 10, 21, 12, 0)

    @Test
    fun remindersForMyUpcomingGigsOnly() {
        val band = base.apply(
            "u-bob",
            CreateEvent(
                "gig", "Club gig", LocalDate(2026, 10, 25), participants = listOf(Participant("alice"), Participant("bob")),
                agenda = listOf(AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0))),
            ),
            CreateEvent("bobs", "Bob only", LocalDate(2026, 10, 26), participants = listOf(Participant("bob"))),
            CreateEvent("soon", "Tomorrow", LocalDate(2026, 10, 22), location = "Klub", mapUrl = "https://maps.app.goo.gl/x", participants = listOf(Participant("alice"))),
            CreateEvent("old", "Archived", LocalDate(2026, 10, 30), participants = listOf(Participant("alice"))),
            SetEventArchived("old", true),
        )
        val reminders = NotificationRules.reminders(listOf(band), "u-alice", setOf(1, 3), LocalTime(18, 0), now)
        assertEquals(
            listOf("soon/1" to LocalDateTime(2026, 10, 21, 18, 0), "gig/3" to LocalDateTime(2026, 10, 22, 18, 0), "gig/1" to LocalDateTime(2026, 10, 24, 18, 0)),
            reminders.map { "${it.eventId}/${it.daysBefore}" to it.at },
            "past reminders (3 days before tomorrow's gig) and other people's or archived gigs are left out",
        )
        assertEquals(LocalTime(17, 0), reminders.last().firstTime?.time)
        assertEquals(listOf("https://maps.app.goo.gl/x", null, null), reminders.map { it.navigateUrl }, "Navigate on the reminder when there is somewhere to go")
    }

    @Test
    fun newTransfersThatOthersRecordedWithMe() {
        val gig = base.apply(
            "u-bob",
            SetTreasurer("alice"),
            CreateEvent("gig", "Club gig", LocalDate(2026, 10, 1), participants = listOf(Participant("alice"), Participant("bob"))),
            AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
        )
        val seen = NotificationRules.transferKeys(listOf(gig))
        val after = gig
            .apply("u-bob", RecordTransfer("gig", "t1", "bob", "alice", Money(5000)), RecordTransfer("gig", "t2", "bob", BAND_FUND, Money(100)))
            .apply("u-alice", RecordTransfer("gig", "t3", "alice", "bob", Money(1)))
        val notices = NotificationRules.newTransfers(listOf(after), "u-alice", seen)
        assertEquals(listOf(listOf("t1", "t2")), notices.map { notice -> notice.transfers.map { it.id } }, "to me and to the fund I hold; not my own")
        assertEquals(listOf("bob"), notices.single().recordedBy)
        assertTrue(NotificationRules.newTransfers(listOf(after), "u-alice", NotificationRules.transferKeys(listOf(after))).isEmpty())
    }

    @Test
    fun theFirstUpcomingGigNamesWhoAddedMe() {
        val band = base.apply("u-bob", CreateEvent("gig", "Club gig", LocalDate(2026, 10, 25), participants = listOf(Participant("alice"))))
        val (_, event) = NotificationRules.firstUpcomingGig(listOf(band), "u-alice", LocalDate(2026, 10, 21))!!
        assertEquals("bob", event.participant("alice")!!.addedBy)
    }

    @Test
    fun aNewlyJoinedBandOrOneMissingForARunDoesNotFlood() {
        val gig = base.apply(
            "u-bob",
            CreateEvent("gig", "Club gig", LocalDate(2026, 10, 1), participants = listOf(Participant("alice"), Participant("bob"))),
            AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
            RecordTransfer("gig", "old", "bob", "alice", Money(5000)),
        )
        // First sight of the band (joined elsewhere): its old transfer is only recorded.
        val first = NotificationRules.checkTransfers(listOf(gig), "u-alice", seen = emptySet(), baselined = emptySet())
        assertTrue(first.notices.isEmpty())
        // Missing from one run (e.g. not loaded): its keys are kept...
        val missing = NotificationRules.checkTransfers(emptyList(), "u-alice", first.seen, first.baselined)
        // ...so when it is back, only the genuinely new transfer is notified.
        val back = gig.apply("u-bob", RecordTransfer("gig", "new", "bob", "alice", Money(1)))
        val later = NotificationRules.checkTransfers(listOf(back), "u-alice", missing.seen, missing.baselined)
        assertEquals(listOf("new"), later.notices.flatMap { notice -> notice.transfers.map { it.id } })
    }
}
