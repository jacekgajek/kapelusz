package com.jacekgajek.kapelusz.app.notify

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberRole
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

    /** ITERATION_4 §1.1: the notice would name who paid them. */
    @Test
    fun artistsGetNoTransferNotices() {
        val gig = base.apply(
            "u-bob",
            AddMember("carol", "Carol"),
            CreateEvent("gig", "Club gig", LocalDate(2026, 10, 1), participants = listOf(Participant("bob"), Participant("carol"))),
            AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
        ).apply("system", LinkUser("carol", "u-carol", Role.ARTIST))
        val seen = NotificationRules.transferKeys(listOf(gig))
        val paid = gig.apply("u-bob", RecordTransfer("gig", "t", "bob", "carol", Money(5000)))
        assertTrue(NotificationRules.newTransfers(listOf(paid), "u-carol", seen).isEmpty())
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

    /** ITERATION_4 §2.2: the lead who recorded a fee the organiser owes is reminded until it comes. */
    @Test
    fun theRecordingLeadIsRemindedOfAnAwaitedFee() {
        val band = base.apply(
            "u-bob",
            CreateEvent("gig", "Club gig", LocalDate(2026, 10, 1), participants = listOf(Participant("alice"), Participant("bob"))),
            AddHolding("gig", "fee", ORGANISER, Money(300000), IncomeSource.CONTRACT),
        )
        val bobs = debtReminders(band, "u-bob")
        assertEquals(listOf("b/gig/fee0", "b/gig/fee1", "b/gig/fee2", "b/gig/fee3"), bobs.map { it.key })
        assertEquals(Money(300000), bobs.first().organiserOwes)
        assertEquals(emptyList(), debtReminders(band, "u-alice"), "another lead: not theirs to chase")
        val due = NotificationRules.dueDebtReminder(listOf(band), "u-bob", "b/gig/fee1", LocalTime(18, 0), LocalDateTime(2026, 10, 29, 18, 30))
        assertEquals(Money(300000), due?.organiserOwes, "checked again when its alarm goes off")
        val received = band.apply("u-bob", ReceiveAwaited("gig", "fee", "paid", "bob", Money(300000)))
        assertTrue(debtReminders(received, "u-bob").none { it.organiserOwes != null }, "paid")
        // Bob is no longer a lead: the leads in the event chase it (Alice, the owner, plays).
        val demoted = band.apply("system", SetMemberRole("bob", Role.MEMBER))
        assertEquals(Money(300000), debtReminders(demoted, "u-alice").first().organiserOwes)
    }

    @Test
    fun aReminderAndroidDeliversLateIsNotDroppedByPlanningAgain() {
        val band = base.apply("u-bob", CreateEvent("gig", "Club gig", LocalDate(2026, 10, 22), participants = listOf(Participant("alice"))))
        // Due at 18:00 the day before; it is 19:00 and Android has not shown it yet.
        val late = LocalDateTime(2026, 10, 21, 19, 0)
        assertEquals(listOf("gig/1"), NotificationRules.reminders(listOf(band), "u-alice", setOf(1), LocalTime(18, 0), late).map { "${it.eventId}/${it.daysBefore}" })
        assertEquals(emptyList(), NotificationRules.reminders(listOf(band), "u-alice", setOf(1), LocalTime(18, 0), late, shown = setOf("band1/gig/1".let { key -> NotificationRules.reminders(listOf(band), "u-alice", setOf(1), LocalTime(18, 0), late).single().key })))
        // Hours later it is no longer worth showing.
        assertEquals(emptyList(), NotificationRules.reminders(listOf(band), "u-alice", setOf(1), LocalTime(18, 0), LocalDateTime(2026, 10, 21, 21, 0)))
    }

    // Bob collected 100 at a gig on 1 Oct shared with Alice: he still has to send her 50.
    private val debt = base.apply(
        "u-bob",
        CreateEvent("gig", "Club gig", LocalDate(2026, 10, 1), participants = listOf(Participant("alice"), Participant("bob"))),
        AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
    )

    private fun debtReminders(band: Band, user: String, now: LocalDateTime = LocalDateTime(2026, 10, 1, 12, 0)) =
        NotificationRules.debtReminders(listOf(band), user, LocalTime(18, 0), now)

    @Test
    fun debtRemindersEveryTwoWeeksAfterTheGigAtMostFour() {
        val reminders = debtReminders(debt, "u-bob")
        assertEquals(
            listOf(LocalDateTime(2026, 10, 15, 18, 0), LocalDateTime(2026, 10, 29, 18, 0), LocalDateTime(2026, 11, 12, 18, 0), LocalDateTime(2026, 11, 26, 18, 0)),
            reminders.map { it.at },
        )
        assertEquals(listOf("b/gig/debt0", "b/gig/debt1", "b/gig/debt2", "b/gig/debt3"), reminders.map { it.key })
        assertEquals(listOf(Owed("Alice", Money(5000))), reminders.first().owed)
        // Four weeks in, after the second: the last two are left.
        assertEquals(listOf(2, 3), debtReminders(debt, "u-bob", LocalDateTime(2026, 10, 30, 12, 0)).map { it.nth })
        assertEquals(emptyList(), debtReminders(debt, "u-bob", LocalDateTime(2026, 11, 27, 12, 0)), "never more than four")
    }

    @Test
    fun onlyThePayerIsRemindedUntilSettled() {
        assertEquals(emptyList(), debtReminders(debt, "u-alice"), "the one owed money is not reminded")
        assertEquals(emptyList(), debtReminders(debt.apply("u-bob", RecordTransfer("gig", "t", "bob", "alice", Money(5000))), "u-bob"), "settled")
        // Part paid: the rest is reminded of.
        assertEquals(listOf(Owed("Alice", Money(3000))), debtReminders(debt.apply("u-bob", RecordTransfer("gig", "t", "bob", "alice", Money(2000))), "u-bob").first().owed)
    }

    @Test
    fun debtsToAndFromTheBandFund() {
        // To the fund: Bob, who collected, also owes the fund its 20.
        val toFund = debt.apply("u-bob", SetTreasurer("alice"), SetFundAmount("gig", Money(2000)))
        assertEquals(listOf(Owed("Alice", Money(4000)), Owed(null, Money(2000))), debtReminders(toFund, "u-bob").first().owed)
        assertEquals(emptyList(), debtReminders(toFund, "u-alice"))
        // From the fund: Alice holds it and collected; she owes Bob his share, part of it out of the fund.
        val fromFund = base.apply(
            "u-bob",
            SetTreasurer("alice"),
            CreateEvent("gig", "Club gig", LocalDate(2026, 10, 1), participants = listOf(Participant("alice"), Participant("bob"))),
            AddHolding("gig", "h", "alice", Money(10000), IncomeSource.DOOR),
            SetFundAmount("gig", Money(-2000)),
        )
        assertEquals(listOf(Owed("Bob", Money(4000)), Owed("Bob", Money(2000), fromFund = true)), debtReminders(fromFund, "u-alice").first().owed)
        assertEquals(emptyList(), debtReminders(fromFund, "u-bob"))
    }

    @Test
    fun aDebtReminderIsCheckedAgainWhenItsAlarmGoesOff() {
        // The second reminder, due on 29 Oct at 18:00; Android delivers it a little late.
        val firing = LocalDateTime(2026, 10, 29, 18, 30)
        fun due(band: Band) = NotificationRules.dueDebtReminder(listOf(band), "u-bob", "b/gig/debt1", LocalTime(18, 0), firing)
        assertEquals(listOf(Owed("Alice", Money(5000))), due(debt)?.owed)
        assertEquals(null, due(debt.apply("u-bob", RecordTransfer("gig", "t", "bob", "alice", Money(5000)))), "paid since it was planned")
        // Part paid since: the text is built from what is still owed.
        assertEquals(listOf(Owed("Alice", Money(3000))), due(debt.apply("u-bob", RecordTransfer("gig", "t", "bob", "alice", Money(2000))))?.owed)
        assertEquals(null, NotificationRules.dueDebtReminder(listOf(debt), "u-bob", "b/gig/debt1", LocalTime(18, 0), LocalDateTime(2026, 10, 29, 23, 0)), "hours late")
        assertEquals(null, NotificationRules.dueDebtReminder(listOf(debt), "u-bob", "b/other/debt1", LocalTime(18, 0), firing), "event gone")
    }
}
