package com.jacekgajek.artsplit.app.notify

import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.RecordTransfer
import com.jacekgajek.artsplit.ops.SetEventArchived
import com.jacekgajek.artsplit.ops.SetTreasurer
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
            CreateEvent("soon", "Tomorrow", LocalDate(2026, 10, 22), participants = listOf(Participant("alice"))),
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
}
