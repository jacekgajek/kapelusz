package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.HistoryResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.BandPart
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.Undo
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The history byte budget, logs without changes, and how the server resolves undos from its log (ITERATION_3 R14). */
class HistoryAndUndoTest {
    private val store = InMemoryBlobStore()
    /** In 2026-09; every push one millisecond later, so the log's order is the order of the pushes. */
    private var now = 1_790_000_000_000
    private val api = Api(store, { token -> VerifiedUser(token, null, null) }, clock = { now })
    private var seq = 0L

    private fun call(method: String, path: String, user: String, body: String? = null, query: Map<String, String> = emptyMap(), api: Api = this.api) =
        api.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body, query))

    private fun <T> HttpResponse.parse(serializer: KSerializer<T>): T {
        assertEquals(200, status, body)
        return KapeluszJson.decodeFromString(serializer, body!!)
    }

    private fun push(user: String, vararg ops: Operation, bandId: String = "band1"): PushResponse {
        now++
        val request = PushRequest(ops.map { seq++; OpEnvelope("op$seq", "phone", seq, 0, it) })
        return call("POST", "/bands/$bandId/ops", user, KapeluszJson.encodeToString(PushRequest.serializer(), request)).parse(PushResponse.serializer())
    }

    private fun createBand(bandId: String = "band1") = call(
        "POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest(bandId, "Night Owls", "PLN", "m-alice", "Alice")),
    ).parse(com.jacekgajek.kapelusz.api.BandStateResponse.serializer())

    /** Links [userId] to [memberId] the way an accepted invite does. */
    private fun link(memberId: String, userId: String, role: Role) {
        val blob = store.get(StorageKeys.band("band1"))!!
        val band = KapeluszJson.decodeFromString(Band.serializer(), blob.content)
        val linked = (Engine.apply(band, Actor.System, LinkUser(memberId, userId, role), "link-$memberId", 0) as ApplyResult.Applied).band
        store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(Band.serializer(), linked), ifMatch = blob.etag)
    }

    private fun log(user: String = "alice", month: String = "2026-09", before: String? = null, api: Api = this.api) =
        call("GET", "/bands/band1/log", user, query = mapOfNotNull("month" to month, before?.let { "before" to it }), api = api)
            .parse(HistoryResponse.serializer())

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
        store.put(StorageKeys.logEntry(bandId, name), KapeluszJson.encodeToString(HistoryEntry.serializer(), entry))
        return name
    }

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
    fun entriesGoneBetweenListingAndReadingAreSkipped() {
        createBand()
        val (oldest, gone, newest) = (1L..3L).map { seed("2026-08", 1_000_000_000_000 + it) }
        // Listed, but deleted before it was read (e.g. by a band deleted meanwhile).
        val vanishing = object : BlobStore by store {
            override fun get(key: String) = if (key.endsWith(gone)) null else store.get(key)
        }
        val api = Api(vanishing, { VerifiedUser(it, null, null) }, clock = { now })
        assertEquals(listOf(newest, oldest), wholeMonth("2026-08", api).map { it.id })
    }

    // ---- dropped oversize changes ----

    @Test
    fun aHugeBatchIsLoggedWithoutItsChangesAndCannotBeUndone() {
        createBand()
        // 500 events with long notes: well within the band's size, but their changes are over what is logged.
        val events = (1..Api.MAX_OPS_PER_PUSH).map { i ->
            CreateEvent("e$i", "Gig $i".padEnd(100, '.'), LocalDate(2026, 9, 1), location = "l".repeat(500), notes = "n".repeat(2000))
        }
        val pushed = push("alice", *events.toTypedArray())
        assertEquals(Api.MAX_OPS_PER_PUSH, pushed.accepted.size)
        assertTrue(KapeluszJson.encodeToString(Band.serializer(), pushed.band).length > HistoryHandlers.MAX_LOGGED_CHANGES)
        val entry = log().entries.first()
        assertEquals(Api.MAX_OPS_PER_PUSH, entry.ops.size, "the operations are still listed")
        assertTrue(entry.changes.isEmpty(), "but not what they changed")

        val undone = push("alice", Undo("op1", events.first(), BandChange(BandPart(), BandPart()), entry.id))
        assertEquals(RejectionReason.NOT_UNDOABLE, undone.rejected.values.single().reason)

        // A small push afterwards is logged with its change again.
        push("alice", RenameBand("Small"))
        assertTrue(log().entries.first().changes.isNotEmpty())
    }

    // ---- undo resolution ----

    @Test
    fun undosOfMalformedOrMissingEntriesAreRefused() {
        createBand()
        push("alice", RenameBand("New name")) // op1
        push("alice", RenameBand("Newer name")) // op2, the one undone below
        val real = log().entries.first { entry -> entry.ops.any { it.opId == "op2" } }
        val realName = real.id!!
        val realEntry = HistoryEntry(real.userId, real.memberId, real.at, real.ops, real.changes)
        // An entry from before changes were recorded, and one listing the operation twice (corrupted).
        val unrecorded = "2026-09/1790000000001-${UUID.randomUUID()}.json"
        store.put(StorageKeys.logEntry("band1", unrecorded), KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry.copy(changes = emptyMap())))
        val twice = "2026-09/1790000000002-${UUID.randomUUID()}.json"
        store.put(StorageKeys.logEntry("band1", twice), KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry.copy(ops = realEntry.ops + realEntry.ops)))
        // The same entry, but in another band's log: an undo only reads its own band's.
        createBand("band2")
        val elsewhere = "2026-09/1790000000003-${UUID.randomUUID()}.json"
        store.put(StorageKeys.logEntry("band2", elsewhere), KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry))

        // An entry that is not JSON any more: its undo is refused, the history around it stays readable.
        val corrupt = "2026-09/1790000000004-${UUID.randomUUID()}.json"
        store.put(StorageKeys.logEntry("band1", corrupt), "{not json")
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
            val result = push("alice", undo(name))
            assertEquals(RejectionReason.NOT_UNDOABLE, result.rejected.values.single().reason, "entry $name")
            assertEquals("Newer name", result.band.name)
        }
        // The real entry still works: the refusals took nothing away.
        assertEquals("New name", push("alice", undo(realName)).band.name)
    }

    @Test
    fun anUndoIsPermittedByTheRecordedOperationNotTheClaimedOne() {
        createBand()
        push("alice", AddMember("m-bob", "Bob"), AddMember("m-vera", "Vera"))
        link("m-bob", "bob", Role.MEMBER)
        link("m-vera", "vera", Role.VIEWER)
        push("alice", RenameBand("Alice's name")) // op3: admins only
        val rename = log().entries.first().id
        // Bob claims the entry holds his own cost (which he may undo); the server reads the rename from the log.
        val claimed = Undo("op3", AddCost("c-bob", "m-bob", Money(100), LocalDate(2026, 9, 1)), BandChange(BandPart(), BandPart()), rename)
        assertEquals(RejectionReason.ROLE_REQUIRED, push("bob", claimed).rejected.values.single().reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, push("vera", undo(rename, "op3")).rejected.values.single().reason)

        // Bob's own cost he may undo; Alice's he may not.
        push("bob", AddCost("c-bob", "m-bob", Money(100), LocalDate(2026, 9, 1))) // op6
        val bobsCost = log().entries.first().id
        push("alice", AddCost("c-alice", "m-alice", Money(100), LocalDate(2026, 9, 1))) // op7
        val alicesCost = log().entries.first().id
        assertEquals(RejectionReason.ROLE_REQUIRED, push("bob", undo(alicesCost, "op7")).rejected.values.single().reason)
        val undone = push("bob", undo(bobsCost, "op6"))
        assertTrue(undone.rejected.isEmpty(), undone.rejected.toString())
        assertNull(undone.band.cost("c-bob"))
        assertTrue(undone.band.cost("c-alice") != null)
    }

    // ---- attachments ----

    @Test
    fun attachmentFilesFollowTheRolesAndArchivedMembersLoseAccess() {
        createBand()
        push("alice", AddMember("m-bob", "Bob"), AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)))
        link("m-bob", "bob", Role.MEMBER)
        val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 1000)
        // The operation follows the cost's rules: Bob may not attach to Alice's cost ...
        assertEquals(RejectionReason.ROLE_REQUIRED, push("bob", AddAttachment("c1", photo)).rejected.values.single().reason)
        push("alice", AddAttachment("c1", photo))
        // ... but any member may upload the file of a listed attachment (the operation was the check).
        fun upload(user: String) = call("POST", "/bands/band1/attachments", user, KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest("a1")))
        assertEquals(200, upload("bob").status)
        store.files += StorageKeys.attachment("band1", photo)
        assertEquals(200, call("GET", "/bands/band1/attachments/a1", "bob").status)

        push("alice", SetMemberArchived("m-bob", true))
        for (response in listOf(upload("bob"), call("GET", "/bands/band1/attachments/a1", "bob"))) {
            assertEquals(404, response.status)
            assertTrue(ApiErrors.BAND_NOT_FOUND in response.body!!)
        }
    }
}
