package com.jacekgajek.kapelusz.wire

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.RecordedRejection
import com.jacekgajek.kapelusz.model.Refund
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.ChangeLineup
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ReceiveAwaited
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.RenameAttachment
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.RenameGuest
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetBankAccount
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetFundBankAccount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundDefault
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetMemberShare
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnknownOperation
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.ops.UpdateHolding
import com.jacekgajek.kapelusz.rules.Actor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * The samples whose JSON [WireFixtures] pins. Everything here is deterministic (no clocks, no random ids), so the
 * fixtures can be regenerated and compared byte for byte.
 */
object WireSamples {
    val date = LocalDate(2026, 9, 13)
    private const val IBAN = "PL61109010140000071219812874"

    private fun pln(text: String) = Money.parse(text)

    fun initialBand(): Band = Band.create("band", "Test band", "PLN", "alice", "Alice", "user-alice")

    /**
     * One of every operation kind, with every field set to something other than its default, so every field name
     * is in the fixture. These are only encoded, never applied.
     */
    val everyOperation: List<Operation> = listOf(
        UnknownOperation("gig"),
        RenameBand("The Night Owls"),
        AddMember("eve", "Eve"),
        RenameMember("eve", "Eva"),
        SetMemberArchived("eve", true),
        DeleteMember("eve"),
        SetMemberShare("bob", 150, pln("20"), listOf("gig", "second"), 10),
        SetMemberRole("bob", Role.LEAD),
        LinkUser("bob", "user-bob", Role.MEMBER),
        UnlinkUser("bob"),
        SetBlikPhone("alice", "+48600123456"),
        SetBankAccount("alice", IBAN),
        SetTreasurer("alice"),
        DisableBandFund,
        ScheduleBandDeletion(BandDeletion("alice", 1_000L, 2_000L)),
        CancelBandDeletion,
        SetFundBlikPhone("600123456"),
        SetFundBankAccount(IBAN),
        TransferOwnership("bob"),
        InviteToSettleTogether("alice", "bob"),
        CancelSettleTogether("alice", "bob"),
        AcceptSettleTogether("alice", "bob"),
        DeclineSettleTogether("alice", "bob"),
        StopSettlingTogether("alice", "bob"),
        CreateEvent(
            eventId = "gig",
            name = "Club gig",
            date = date,
            location = "Warsaw",
            notes = "Bring cables",
            guests = listOf(Guest("helper", "Helper")),
            participants = listOf(
                Participant("alice"),
                Participant("bob", 200, "alice", pln("30")),
                Participant("helper", 50, null, null, 10),
            ),
            agenda = listOf(AgendaTime("a1", AgendaKind.ARRIVAL, "Arrival", LocalTime(18, 0), fromTemplate = true)),
            fund = FundCut(pln("50"), 10),
        ),
        SetEventAgenda("gig", listOf(AgendaTime("a2", AgendaKind.CONCERT_START, "Show", LocalTime(20, 30)))),
        SetAgendaTemplate(listOf(AgendaTemplateItem(AgendaKind.SOUND_CHECK, "Check"), AgendaTemplateItem(null, "Dinner"))),
        UpdateEvent("gig", Change("Club gig II"), Change(date), Change(null), Change("Notes")),
        SetEventArchived("gig", true),
        DeleteEvent("gig"),
        AddParticipant("gig", "carol", 150, pln("40"), 20),
        AddGuest("gig", "g2", "Second", 150, pln("40"), 20),
        RenameGuest("gig", "g2", "Second guest"),
        SetWeight("gig", "g2", 50),
        SetFixedShare("gig", "eve", pln("40"), 100, 20),
        RemoveParticipant("gig", "g2"),
        ChangeLineup("gig", listOf(Participant("frank"), Participant("g4", 100)), listOf("g2"), listOf(Guest("g4", "Fourth"))),
        AddHolding("gig", "h1", "alice", pln("1000"), IncomeSource.HAT, "hat"),
        ReceiveAwaited("gig", "h2", "h3", "bob", pln("200")),
        UpdateHolding("gig", "h1", Change(pln("1100")), Change(IncomeSource.DOOR), Change(null)),
        RemoveHolding("gig", "h3"),
        SetRefund("gig", "c1", pln("100")),
        SetFundAmount("gig", pln("60"), 10),
        SetFundDefault(FundCut(null, 10), listOf("gig")),
        AddFundEntry("f1", date, "Fees", pln("50"), "c1", "bob"),
        UpdateFundEntry("f1", Change(date), Change("Membership fees"), Change(pln("55")), Change(null)),
        RemoveFundEntry("f1"),
        RecordTransfer("gig", "t1", "alice", "bob", pln("100"), "cash", together = true),
        SettleSuggestions("gig", "alice"),
        RemoveTransfer("gig", "t1"),
        AddCost("c1", "bob", pln("120"), date, "gig", CostCategory.TRAVEL, "Fuel"),
        UpdateCost("c1", Change(pln("130")), Change(date), Change(null), Change(CostCategory.FOOD), Change(null)),
        DeleteCost("c1"),
        AddAttachment(AttachmentOwner.OfCost("c1"), Attachment("att1", AttachmentKind.IMAGE, "receipt.jpg", 12_345)),
        RemoveAttachment(AttachmentOwner.OfHolding("gig", "h1"), "att2"),
        RenameAttachment(AttachmentOwner.OfEvent("gig"), "att3", "stage.jpg"),
        Undo("op0", RenameBand("Old name"), BandChange.between(initialBand(), initialBand().copy(name = "Old name")), "2026-10/1.json"),
    )

    /**
     * A realistic sequence of changes, applied by the system actor from [initialBand]: every kind of entity a stored
     * band can hold ends up in [replay]'s result, and the result is what [WireFixtures.BAND] pins.
     */
    val log: List<OpEnvelope> = run {
        val initial = initialBand()
        val renamed = (Engine.apply(initial, Actor.System, RenameBand("Old name"), "op0", 1_000L) as ApplyResult.Applied).band
        listOf(
            RenameBand("Old name"),
            Undo("op0", RenameBand("Old name"), BandChange.between(initial, renamed), "2026-10/1.json"),
            RenameBand("The Night Owls"),
            AddMember("bob", "Bob"),
            AddMember("carol", "Carol"),
            AddMember("dave", "Dave"),
            AddMember("eve", "Eve"),
            AddMember("frank", "Frank"),
            AddMember("gina", "Gina"),
            AddMember("hank", "Hank"),
            RenameMember("eve", "Eva"),
            DeleteMember("gina"),
            LinkUser("bob", "user-bob", Role.LEAD),
            LinkUser("carol", "user-carol", Role.ARTIST),
            LinkUser("dave", "user-dave", Role.VIEWER),
            SetMemberRole("eve", Role.MEMBER),
            SetMemberShare("bob", 150, null),
            SetMemberShare("carol", 100, pln("20")),
            SetMemberShare("frank", 100, null, percent = 10),
            SetBlikPhone("alice", "+48600123456"),
            SetBankAccount("alice", IBAN),
            SetTreasurer("alice"),
            SetFundBlikPhone("600123456"),
            SetFundBankAccount(IBAN),
            SetFundDefault(FundCut(amount = pln("20"))),
            SetAgendaTemplate(listOf(AgendaTemplateItem(AgendaKind.ARRIVAL), AgendaTemplateItem(null, "Dinner"))),
            InviteToSettleTogether("alice", "bob"),
            DeclineSettleTogether("alice", "bob"),
            InviteToSettleTogether("alice", "bob"),
            CancelSettleTogether("alice", "bob"),
            InviteToSettleTogether("alice", "bob"),
            AcceptSettleTogether("alice", "bob"),
            StopSettlingTogether("alice", "bob"),
            InviteToSettleTogether("alice", "bob"),
            AcceptSettleTogether("alice", "bob"),
            LinkUser("frank", "user-frank", Role.MEMBER),
            InviteToSettleTogether("carol", "frank"),
            CreateEvent(
                eventId = "gig",
                name = "Club gig",
                date = date,
                location = "Warsaw",
                notes = "Bring cables",
                guests = listOf(Guest("helper", "Helper")),
                participants = listOf(Participant("alice"), Participant("bob", 200), Participant("helper", 50)),
                agenda = listOf(AgendaTime("a1", AgendaKind.ARRIVAL, null, LocalTime(18, 0), fromTemplate = true)),
                fund = FundCut(amount = pln("50")),
            ),
            SetEventAgenda(
                "gig",
                listOf(
                    AgendaTime("a1", AgendaKind.ARRIVAL, null, LocalTime(18, 0), fromTemplate = true),
                    AgendaTime("a2", AgendaKind.CONCERT_START, "Show", LocalTime(20, 30)),
                ),
            ),
            UpdateEvent("gig", Change("Club gig II"), null, Change("Warsaw, the club"), Change("Notes")),
            AddParticipant("gig", "carol"),
            AddParticipant("gig", "eve", fixedShare = pln("30")),
            AddGuest("gig", "g2", "Second"),
            AddGuest("gig", "g3", "Third", percent = 10),
            RenameGuest("gig", "g2", "Second guest"),
            SetWeight("gig", "g2", 50),
            SetFixedShare("gig", "eve", pln("40")),
            RemoveParticipant("gig", "helper"),
            ChangeLineup("gig", listOf(Participant("frank"), Participant("g4", 100)), listOf("g2"), listOf(Guest("g4", "Fourth"))),
            AddHolding("gig", "h1", "alice", pln("1000"), IncomeSource.HAT, "hat"),
            AddHolding("gig", "h2", ORGANISER, pln("200"), IncomeSource.CONTRACT, "fee"),
            ReceiveAwaited("gig", "h2", "h3", "bob", pln("200")),
            UpdateHolding("gig", "h1", Change(pln("1100")), Change(IncomeSource.DOOR), Change(null)),
            AddHolding("gig", "h4", "bob", pln("10"), IncomeSource.MERCH),
            RemoveHolding("gig", "h4"),
            AddCost("c1", "bob", pln("120"), date, "gig", CostCategory.TRAVEL, "Fuel"),
            UpdateCost("c1", Change(pln("130")), null, null, Change(CostCategory.FOOD), Change("Fuel for the van")),
            AddCost("c2", "alice", pln("10"), date),
            DeleteCost("c2"),
            AddCost("c3", "alice", pln("25"), date, category = CostCategory.EQUIPMENT),
            SetRefund("gig", "c1", pln("100")),
            SetFundAmount("gig", pln("60")),
            AddAttachment(AttachmentOwner.OfCost("c1"), Attachment("att1", AttachmentKind.IMAGE, "receipt.jpg", 12_345)),
            RenameAttachment(AttachmentOwner.OfCost("c1"), "att1", "fuel.jpg"),
            AddAttachment(AttachmentOwner.OfHolding("gig", "h1"), Attachment("att2", AttachmentKind.PDF, "a.pdf", 10)),
            AddAttachment(AttachmentOwner.OfHolding("gig", "h1"), Attachment("att4", AttachmentKind.PDF, "b.pdf", 11)),
            RemoveAttachment(AttachmentOwner.OfHolding("gig", "h1"), "att4"),
            AddAttachment(AttachmentOwner.OfEvent("gig"), Attachment("att3", AttachmentKind.IMAGE, "stage.jpg", 2_048)),
            RecordTransfer("gig", "t1", "alice", "bob", pln("100"), "cash"),
            RemoveTransfer("gig", "t1"),
            RecordTransfer("gig", "t2", "alice", "carol", pln("10"), "advance"),
            SettleSuggestions("gig"),
            SetEventArchived("gig", true),
            SetEventArchived("gig", false),
            AddFundEntry("f1", date, "Fees", pln("50"), paidBy = "bob"),
            UpdateFundEntry("f1", Change(date), Change("Membership fees"), Change(pln("55")), Change("alice")),
            AddFundEntry("f2", date, "Refund", pln("-25"), costId = "c3"),
            AddFundEntry("f3", date, "Gone", pln("5")),
            RemoveFundEntry("f3"),
            CreateEvent("second", "Second gig", LocalDate(2026, 10, 1)),
            DeleteEvent("second"),
            CreateEvent("third", "Third gig", LocalDate(2026, 11, 1)),
            SetFundAmount("third", Money.ZERO, 10),
            SetEventArchived("third", true),
            // Ending the pair records on the gig that it was settled together; Alice then pairs with Eve.
            StopSettlingTogether("alice", "bob"),
            LinkUser("eve", "user-eve", Role.MEMBER),
            InviteToSettleTogether("alice", "eve"),
            AcceptSettleTogether("alice", "eve"),
            SetMemberArchived("hank", true),
            UnlinkUser("dave"),
            TransferOwnership("bob"),
            ScheduleBandDeletion(BandDeletion("bob", 1_000L, 2_000L)),
            CancelBandDeletion,
        ).mapIndexed { i, op -> OpEnvelope("op$i", "client", i.toLong(), 1_000L + i, op) }
    }

    /**
     * Who sends what in [log]: the owner (Alice) nearly everything, Bob answers her settle-together invitations, the
     * server links users and schedules deletions. Then `addedBy`/`recordedBy` are set like in a real band.
     */
    private fun actorFor(op: Operation): Actor = when (op) {
        is InviteToSettleTogether -> Actor.User("user-${op.fromMemberId}")
        is CancelSettleTogether -> Actor.User("user-${op.fromMemberId}")
        is AcceptSettleTogether -> Actor.User("user-${op.toMemberId}")
        is DeclineSettleTogether -> Actor.User("user-${op.toMemberId}")
        is StopSettlingTogether -> Actor.User("user-${op.memberId}")
        is LinkUser, is ScheduleBandDeletion, is CancelBandDeletion -> Actor.System
        else -> Actor.User("user-alice")
    }

    /** [log] applied in order; a rejection is a bug in the samples, not in the format. */
    fun replay(): Band = log.fold(initialBand()) { band, envelope ->
        when (val result = Engine.apply(band, actorFor(envelope.op), envelope.op, envelope.opId, envelope.timestamp)) {
            is ApplyResult.Applied -> result.band
            is ApplyResult.Rejected -> error("${envelope.opId} ${envelope.op} was rejected: ${result.rejection}")
        }
    }

    /** [replay] plus what only the server writes: sync bookkeeping, photos, a pending deletion, a pair's settled events. */
    fun storedBand(): Band {
        val band = replay()
        val refused = Rejection(RejectionCode.FORBIDDEN, "Leaders only", RejectionReason.ROLE_REQUIRED)
        return band.copy(
            members = band.members.map { if (it.id == "alice") it.copy(pictureUrl = "https://lh3.googleusercontent.com/a/photo") else it },
            deletion = BandDeletion("bob", 1_000L, 2_000L),
            lastSeq = mapOf("user-alice/client" to 83L),
            recentRejections = mapOf("client" to listOf(RecordedRejection("op99", refused))),
        )
    }

    /**
     * A server history entry with two changes: everything the log added (every entity kind and setting, as "before"
     * absent and "after" present), and a removal (a cost and a member gone, so their orders are recorded too).
     */
    val historyEntry: HistoryEntry = run {
        val stored = storedBand()
        val fewer = stored.copy(costs = stored.costs.filter { it.id != "c3" }, members = stored.members.filter { it.id != "hank" })
        val changes = mapOf("op2" to BandChange.between(initialBand(), stored), "op99" to BandChange.between(stored, fewer))
        HistoryEntry("user-alice", "alice", 1_234L, listOf(log[2]), changes, "2026-10/1234-abc.json")
    }
}
