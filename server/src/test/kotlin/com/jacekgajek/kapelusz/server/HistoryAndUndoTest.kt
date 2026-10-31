package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.HistoryResponse
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.history.HistoryLog
import com.jacekgajek.kapelusz.server.store.HistoryRecord
import com.jacekgajek.kapelusz.server.store.StorageKeys
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** The history byte budget, logs without changes, and how the server resolves undos from its log (ITERATION_3 R14). */
class HistoryAndUndoTest : ApiHarness() {
    /** In 2026-09; every push one millisecond later, so the log's order is the order of the pushes. */
    override fun push(user: String, vararg ops: Operation, bandId: String, clientId: String): HttpResponse {
        now++
        return super.push(user, *ops, bandId = bandId, clientId = clientId)
    }

    private fun log(user: String = "alice", month: String = "2026-09", before: String? = null, api: Api = this.api): HistoryResponse {
        val query = mapOfNotNull("month" to month, before?.let { "before" to it })
        return call("GET", "/bands/band1/log", user, query = query, on = api).parse(HistoryResponse.serializer())
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String>?) = pairs.filterNotNull().toMap()

    /** Every entry of [month], following the `next` cursors. */
    private fun wholeMonth(month: String, api: Api = this.api): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        var before: String? = null
        do {
            val page = log(month = month, before = before, api = api)
            entries += page.entries
            before = page.next
        } while (before != null)
        return entries
    }

    /** Writes a history entry of about [chars] characters directly to the log, and returns its name. */
    private fun seed(month: String, at: Long, chars: Int = 100, changes: Map<String, BandChange> = emptyMap(), bandId: String = "band1"): String {
        val name = "$month/$at-${UUID.randomUUID()}.json"
        val entry = HistoryEntry("alice", "m-alice", at, listOf(OpEnvelope("seeded$at", "alice/phone", at, at, RenameBand("x".repeat(chars)))), changes)
        record(bandId, name, KapeluszJson.encodeToString(HistoryEntry.serializer(), entry))
        return name
    }

    /** Stores [content] as the only operation of the push [name], the way the server records one. */
    private fun record(bandId: String, name: String, vararg content: String) = history.put(bandId, content.mapIndexed { index, it -> HistoryRecord("$name#${index.toString().padStart(3, '0')}", it) })

    private fun undo(entry: String?, undoneOpId: String = "op2") = Undo(undoneOpId, RenameBand("claimed"), BandChange(BandPart(), BandPart()), entry)

    // ---- several months in one request ----

    @Test
    fun severalMonthsComeInOneAnswerNewestFirst() {
        createBand()
        val july = seed("2026-07", 1_000_000_000_001)
        val (augustOld, augustNew) = (2L..3L).map { seed("2026-08", 1_000_000_000_000 + it) }
        val september = seed("2026-09", 1_000_000_000_004)
        val june = seed("2026-06", 1_000_000_000_000)
        val page = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "3")).parse(HistoryResponse.serializer())
        assertEquals(listOf(september, augustNew, augustOld, july), page.entries.map { it.id }, "June is a fourth month back")
        assertEquals("2026-09", page.month)
        assertNull(page.next)
        // Across a year boundary.
        val january = seed("2027-01", 1_000_000_000_005)
        val december = seed("2026-12", 1_000_000_000_006)
        val turn = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2027-01", "months" to "2")).parse(HistoryResponse.serializer())
        assertEquals(listOf(january, december), turn.entries.map { it.id })
        assertEquals(listOf(june), log(month = "2026-06").entries.map { it.id }, "one month by default")
    }

    @Test
    fun aBusyMonthDoesNotCrowdOutTheOthers() {
        createBand()
        val august = seed("2026-08", 1_000_000_000_000)
        val september = (1L..Api.HISTORY_PAGE + 1L).map { seed("2026-09", 1_000_000_000_000 + it) }
        val page = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "2")).parse(HistoryResponse.serializer())
        assertEquals(september.reversed().take(Api.HISTORY_PAGE) + august, page.entries.map { it.id }, "at most a page a month")
    }

    @Test
    fun aLargeMonthDoesNotUseUpTheOthersBytes() {
        createBand()
        val august = seed("2026-08", 1_000_000_000_000, chars = 400_000)
        val (older, newer) = (1L..2L).map { seed("2026-09", 1_000_000_000_000 + it, chars = 400_000) }
        val page = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "3")).parse(HistoryResponse.serializer())
        // A third of the budget a month: one 400 kB entry each.
        assertEquals(listOf(newer, august), page.entries.map { it.id }, "$older is over September's share")
    }

    @Test
    fun aMonthsRangeIsCheckedAndNotPaged() {
        createBand()
        for (months in listOf("0", "4", "x")) {
            assertEquals(400, call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to months)).status, months)
        }
        assertEquals(400, call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "2", "before" to "1")).status)
        assertEquals(400, call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-13")).status)
        assertEquals(404, call("GET", "/bands/band1/log", "bob", query = mapOf("month" to "2026-09", "months" to "3")).status, "members only")
    }

    // ---- the history byte budget ----

    @Test
    fun aPageStopsAtTheByteBudget() {
        createBand()
        val names = (1L..5L).map { seed("2026-08", 1_000_000_000_000 + it, chars = 400_000) }
        val first = log(month = "2026-08")
        // Three entries of 400 kB fit into the 1.5 MB budget; a fourth would not.
        assertEquals(names.reversed().take(3), first.entries.map { it.id })
        assertEquals(names[2].removePrefix("2026-08/"), first.next)
        val second = log(month = "2026-08", before = first.next)
        assertEquals(names.take(2).reversed(), second.entries.map { it.id })
        assertNull(second.next)
    }

    @Test
    fun anEntryOverTheBudgetStillComesOnAPageOfItsOwn() {
        createBand()
        val huge = seed("2026-08", 1_000_000_000_001, chars = Api.HISTORY_PAGE_BYTES + 1)
        val small = seed("2026-08", 1_000_000_000_002)
        val first = log(month = "2026-08")
        assertEquals(listOf(small), first.entries.map { it.id }, "the huge one does not join a page that has entries")
        val second = log(month = "2026-08", before = first.next)
        assertEquals(listOf(huge), second.entries.map { it.id }, "but is shown alone, so the history never gets stuck")
        assertNull(second.next)
    }

    @Test
    fun aPushComesBackAsOneEntryAndAnUnreadableOperationLeavesOutOnlyItself() {
        createBand()
        pushed("alice", RenameBand("One"), RenameBand("Two"), RenameBand("Three"))
        val entry = log().entries.first()
        assertEquals(listOf("One", "Two", "Three"), entry.ops.map { (it.op as RenameBand).name }, "in the order pushed")
        assertEquals(entry.ops.map { it.opId }.toSet(), entry.changes.keys)
        // The middle operation's record is damaged: the other two still show, and can still be undone.
        val band = history.bands.getValue("band1")
        band["${entry.id}#001"] = "{not json"
        assertEquals(listOf("One", "Three"), log().entries.first().ops.map { (it.op as RenameBand).name })
        val last = entry.ops.last()
        assertTrue(pushed("alice", Undo(last.opId, last.op, BandChange(BandPart(), BandPart()), entry.id)).rejected.isEmpty())
    }

    @Test
    fun aRecordTooLargeForTheTableKeepsItsOperationWithoutTheChange() {
        createBand()
        val band = Band.create("band1", "Night Owls", "PLN", "m-alice", "Alice", "alice")
        val historyLog = HistoryLog(history) { now }
        val small = OpEnvelope("small", "phone", 1, 0, RenameBand("A"))
        val large = OpEnvelope("large", "phone", 2, 0, RenameBand("B"))
        // Under the push's limit together, but one operation's change alone is over what one record takes.
        val event = Event("e1", "Gig", LocalDate(2026, 9, 1), notes = "n".repeat(HistoryLog.MAX_RECORD_BYTES))
        val changes = mapOf(
            "small" to BandChange(BandPart(), BandPart()),
            "large" to BandChange(BandPart(events = mapOf("e1" to null)), BandPart(events = mapOf("e1" to event))),
        )
        historyLog.append(VerifiedUser("alice", null, null), "m-alice", "band1", listOf(small, large), changes)
        assertTrue(history.bands.getValue("band1").values.all { it.length <= HistoryLog.MAX_RECORD_BYTES })
        val entry = log().entries.single()
        assertEquals(listOf("small", "large"), entry.ops.map { it.opId }, "both operations are listed")
        assertEquals(setOf("small"), entry.changes.keys, "only the small one can be undone")
    }

    @Test
    fun deletingTheBandDeletesItsHistory() {
        createBand()
        pushed("alice", RenameBand("Gone soon"))
        assertTrue(history.bands.getValue("band1").isNotEmpty())
        assertEquals(204, call("DELETE", "/bands/band1", "alice").status)
        assertTrue(history.bands.getValue("band1").isNotEmpty(), "kept while the band waits (ITERATION_7 §9)")
        now += com.jacekgajek.kapelusz.model.BandDeletion.DELAY_MS
        assertEquals(404, call("GET", "/bands/band1/state", "alice").status)
        assertTrue(history.bands["band1"].isNullOrEmpty())
    }

    // ---- dropped oversize changes ----

    @Test
    fun aHugeBatchIsLoggedWithoutItsChangesAndCannotBeUndone() {
        createBand()
        // 500 events with long notes: well within the band's size, but their changes are over what is logged.
        val events = (1..Api.MAX_OPS_PER_PUSH).map { i ->
            CreateEvent("e$i", "Gig $i".padEnd(100, '.'), LocalDate(2026, 9, 1), location = "l".repeat(500), notes = "n".repeat(2000))
        }
        val result = pushed("alice", *events.toTypedArray())
        assertEquals(Api.MAX_OPS_PER_PUSH, result.accepted.size)
        assertTrue(KapeluszJson.encodeToString(Band.serializer(), result.band).length > HistoryLog.MAX_LOGGED_CHANGES)
        val entry = log().entries.first()
        assertEquals(Api.MAX_OPS_PER_PUSH, entry.ops.size, "the operations are still listed")
        assertTrue(entry.changes.isEmpty(), "but not what they changed")

        val undone = pushed("alice", Undo("op1", events.first(), BandChange(BandPart(), BandPart()), entry.id))
        assertEquals(RejectionReason.NOT_UNDOABLE, undone.rejected.values.single().reason)

        // A small push afterwards is logged with its change again.
        pushed("alice", RenameBand("Small"))
        assertTrue(log().entries.first().changes.isNotEmpty())
    }

    // ---- undo resolution ----

    @Test
    fun undosOfMalformedOrMissingEntriesAreRefused() {
        createBand()
        pushed("alice", RenameBand("New name")) // op1
        pushed("alice", RenameBand("Newer name")) // op2, the one undone below
        val real = log().entries.first { entry -> entry.ops.any { it.opId == "op2" } }
        val realName = real.id!!
        val realEntry = HistoryEntry(real.userId, real.memberId, real.at, real.ops, real.changes)
        // An entry from before changes were recorded, and one listing the operation twice (corrupted).
        val unrecorded = "2026-09/1790000000001-${UUID.randomUUID()}.json"
        record("band1", unrecorded, KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry.copy(changes = emptyMap())))
        val twice = "2026-09/1790000000002-${UUID.randomUUID()}.json"
        KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry).let { record("band1", twice, it, it) }
        // The same entry, but in another band's log: an undo only reads its own band's.
        createBand(bandId = "band2")
        val elsewhere = "2026-09/1790000000003-${UUID.randomUUID()}.json"
        record("band2", elsewhere, KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry))

        // An entry that is not JSON any more: its undo is refused, the history around it stays readable.
        val corrupt = "2026-09/1790000000004-${UUID.randomUUID()}.json"
        record("band1", corrupt, "{not json")
        assertTrue(wholeMonth("2026-09").any { entry -> entry.ops.any { it.opId == "op2" } })

        val refused = listOf(
            corrupt,
            null, // no entry named
            "",
            "2026-09/1-00000000-0000-0000-0000-000000000000.json", // well-formed, not there
            realName.uppercase(),
            "/$realName",
            "$realName/",
            realName.removePrefix("2026-09/"),
            "2026-09/../2026-09/" + realName.removePrefix("2026-09/"),
            unrecorded,
            twice,
            elsewhere,
        )
        for (name in refused) {
            val result = pushed("alice", undo(name))
            assertEquals(RejectionReason.NOT_UNDOABLE, result.rejected.values.single().reason, "entry $name")
            assertEquals("Newer name", result.band.name)
        }
        // The real entry still works: the refusals took nothing away.
        assertEquals("New name", pushed("alice", undo(realName)).band.name)
    }

    @Test
    fun anUndoIsPermittedByTheRecordedOperationNotTheClaimedOne() {
        createBand()
        pushed("alice", AddMember("m-bob", "Bob"), AddMember("m-vera", "Vera"))
        link("m-bob", "bob", Role.MEMBER)
        link("m-vera", "vera", Role.VIEWER)
        pushed("alice", RenameBand("Alice's name")) // op3: admins only
        val rename = log().entries.first().id
        // Bob claims the entry holds his own cost (which he may undo); the server reads the rename from the log.
        val claimed = Undo("op3", AddCost("c-bob", "m-bob", Money(100), LocalDate(2026, 9, 1)), BandChange(BandPart(), BandPart()), rename)
        assertEquals(RejectionReason.ROLE_REQUIRED, pushed("bob", claimed).rejected.values.single().reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, pushed("vera", undo(rename, "op3")).rejected.values.single().reason)

        // Bob's own cost he may undo; Alice's he may not.
        pushed("bob", AddCost("c-bob", "m-bob", Money(100), LocalDate(2026, 9, 1))) // op6
        val bobsCost = log().entries.first().id
        pushed("alice", AddCost("c-alice", "m-alice", Money(100), LocalDate(2026, 9, 1))) // op7
        val alicesCost = log().entries.first().id
        assertEquals(RejectionReason.ROLE_REQUIRED, pushed("bob", undo(alicesCost, "op7")).rejected.values.single().reason)
        val undone = pushed("bob", undo(bobsCost, "op6"))
        assertTrue(undone.rejected.isEmpty(), undone.rejected.toString())
        assertNull(undone.band.cost("c-bob"))
        assertTrue(undone.band.cost("c-alice") != null)
    }

    // ---- attachments ----

    @Test
    fun attachmentFilesFollowTheRolesAndArchivedMembersLoseAccess() {
        createBand()
        pushed("alice", AddMember("m-bob", "Bob"), AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)))
        link("m-bob", "bob", Role.MEMBER)
        val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 1000)
        // The operation follows the cost's rules: Bob may not attach to Alice's cost ...
        assertEquals(RejectionReason.ROLE_REQUIRED, pushed("bob", AddAttachment("c1", photo)).rejected.values.single().reason)
        pushed("alice", AddAttachment("c1", photo))
        // ... but any member may upload the file of a listed attachment (the operation was the check).
        fun upload(user: String) = call("POST", "/bands/band1/attachments", user, KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest("a1")))
        assertEquals(200, upload("bob").status)
        store.files += StorageKeys.attachment("band1", photo)
        assertEquals(200, call("GET", "/bands/band1/attachments/a1", "bob").status)

        pushed("alice", SetMemberArchived("m-bob", true))
        for (response in listOf(upload("bob"), call("GET", "/bands/band1/attachments/a1", "bob"))) {
            assertEquals(404, response.status)
            assertTrue(ApiErrors.BAND_NOT_FOUND in response.body!!)
        }
    }
}
