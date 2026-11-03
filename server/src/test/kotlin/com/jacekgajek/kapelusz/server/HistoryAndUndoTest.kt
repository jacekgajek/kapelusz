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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import kotlinx.datetime.LocalDate

/** The harness of [HistoryAndUndoTest]: every push one millisecond later, and helpers to read and seed the history log. */
private class HistoryApi : ApiHarness() {
    /** In 2026-09; every push one millisecond later, so the log's order is the order of the pushes. */
    override fun push(user: String, vararg ops: Operation, bandId: String, clientId: String): HttpResponse {
        now++
        return super.push(user, *ops, bandId = bandId, clientId = clientId)
    }

    fun log(user: String = "alice", month: String = "2026-09", before: String? = null, api: Api = this.api): HistoryResponse {
        val query = mapOfNotNull("month" to month, before?.let { "before" to it })
        return call("GET", "/bands/band1/log", user, query = query, on = api).parse(HistoryResponse.serializer())
    }

    fun mapOfNotNull(vararg pairs: Pair<String, String>?) = pairs.filterNotNull().toMap()

    /** Every entry of [month], following the `next` cursors. */
    fun wholeMonth(month: String, api: Api = this.api): List<HistoryEntry> {
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
    fun seed(month: String, at: Long, chars: Int = 100, changes: Map<String, BandChange> = emptyMap(), bandId: String = "band1"): String {
        val name = "$month/$at-${UUID.randomUUID()}.json"
        val entry = HistoryEntry(
            "alice",
            "m-alice",
            at,
            listOf(OpEnvelope("seeded$at", "alice/phone", at, at, RenameBand("x".repeat(chars)))),
            changes,
        )
        record(bandId, name, KapeluszJson.encodeToString(HistoryEntry.serializer(), entry))
        return name
    }

    /** Stores [content] as the only operation of the push [name], the way the server records one. */
    fun record(bandId: String, name: String, vararg content: String) = history.put(
        bandId,
        content.mapIndexed { index, text -> HistoryRecord("$name#${index.toString().padStart(3, '0')}", text) },
    )

    fun undo(entry: String?, undoneOpId: String = "op2") = Undo(
        undoneOpId,
        RenameBand("claimed"),
        BandChange(BandPart(), BandPart()),
        entry,
    )
}

/** The history byte budget, logs without changes, and how the server resolves undos from its log. */
class HistoryAndUndoTest :
    ExpectSpec({
        context("several months in one request") {
            expect("several months come in one answer, newest first") {
                with(HistoryApi()) {
                    createBand()
                    val july = seed("2026-07", 1_000_000_000_001)
                    val (augustOld, augustNew) = (2L..3L).map { seed("2026-08", 1_000_000_000_000 + it) }
                    val september = seed("2026-09", 1_000_000_000_004)
                    val june = seed("2026-06", 1_000_000_000_000)
                    val page = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "3")).parse(HistoryResponse.serializer())
                    withClue("June is a fourth month back") {
                        page.entries.map { it.id } shouldBe listOf(september, augustNew, augustOld, july)
                    }
                    page.month shouldBe "2026-09"
                    page.next.shouldBeNull()
                    // Across a year boundary.
                    val january = seed("2027-01", 1_000_000_000_005)
                    val december = seed("2026-12", 1_000_000_000_006)
                    val turn = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2027-01", "months" to "2")).parse(HistoryResponse.serializer())
                    turn.entries.map { it.id } shouldBe listOf(january, december)
                    withClue("one month by default") { log(month = "2026-06").entries.map { it.id } shouldBe listOf(june) }
                }
            }

            expect("a busy month does not crowd out the others") {
                with(HistoryApi()) {
                    createBand()
                    val august = seed("2026-08", 1_000_000_000_000)
                    val september = (1L..Api.HISTORY_PAGE + 1L).map { seed("2026-09", 1_000_000_000_000 + it) }
                    val page = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "2")).parse(HistoryResponse.serializer())
                    withClue("at most a page a month") {
                        page.entries.map { it.id } shouldBe (september.reversed().take(Api.HISTORY_PAGE) + august)
                    }
                }
            }

            expect("a large month does not use up the others' bytes") {
                with(HistoryApi()) {
                    createBand()
                    val august = seed("2026-08", 1_000_000_000_000, chars = 400_000)
                    val (older, newer) = (1L..2L).map { seed("2026-09", 1_000_000_000_000 + it, chars = 400_000) }
                    val page = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "months" to "3")).parse(HistoryResponse.serializer())
                    // A third of the budget a month: one 400 kB entry each.
                    withClue("$older is over September's share") { page.entries.map { it.id } shouldBe listOf(newer, august) }
                }
            }

            expect("a months range is checked and not paged") {
                with(HistoryApi()) {
                    createBand()
                    for (months in listOf("0", "4", "x")) {
                        withClue(months) {
                            call(
                                "GET",
                                "/bands/band1/log",
                                "alice",
                                query = mapOf("month" to "2026-09", "months" to months),
                            ).status shouldBe 400
                        }
                    }
                    call(
                        "GET",
                        "/bands/band1/log",
                        "alice",
                        query = mapOf("month" to "2026-09", "months" to "2", "before" to "1"),
                    ).status shouldBe 400
                    call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-13")).status shouldBe 400
                    withClue("members only") {
                        call("GET", "/bands/band1/log", "bob", query = mapOf("month" to "2026-09", "months" to "3")).status shouldBe 404
                    }
                }
            }
        }

        context("the history byte budget") {
            expect("a page stops at the byte budget") {
                with(HistoryApi()) {
                    createBand()
                    val names = (1L..5L).map { seed("2026-08", 1_000_000_000_000 + it, chars = 400_000) }
                    val first = log(month = "2026-08")
                    // Three entries of 400 kB fit into the 1.5 MB budget; a fourth would not.
                    first.entries.map { it.id } shouldBe names.reversed().take(3)
                    first.next shouldBe names[2].removePrefix("2026-08/")
                    val second = log(month = "2026-08", before = first.next)
                    second.entries.map { it.id } shouldBe names.take(2).reversed()
                    second.next.shouldBeNull()
                }
            }

            expect("an entry over the budget still comes on a page of its own") {
                with(HistoryApi()) {
                    createBand()
                    val huge = seed("2026-08", 1_000_000_000_001, chars = Api.HISTORY_PAGE_BYTES + 1)
                    val small = seed("2026-08", 1_000_000_000_002)
                    val first = log(month = "2026-08")
                    withClue("the huge one does not join a page that has entries") { first.entries.map { it.id } shouldBe listOf(small) }
                    val second = log(month = "2026-08", before = first.next)
                    withClue("but is shown alone, so the history never gets stuck") { second.entries.map { it.id } shouldBe listOf(huge) }
                    second.next.shouldBeNull()
                }
            }
        }

        expect("a push comes back as one entry and an unreadable operation leaves out only itself") {
            with(HistoryApi()) {
                createBand()
                pushed("alice", RenameBand("One"), RenameBand("Two"), RenameBand("Three"))
                val entry = log().entries.first()
                withClue("in the order pushed") { entry.ops.map { (it.op as RenameBand).name } shouldBe listOf("One", "Two", "Three") }
                entry.changes.keys shouldBe entry.ops.map { it.opId }.toSet()
                // The middle operation's record is damaged: the other two still show, and can still be undone.
                val band = history.bands.getValue("band1")
                band["${entry.id}#001"] = "{not json"
                log().entries.first().ops.map { (it.op as RenameBand).name } shouldBe listOf("One", "Three")
                val last = entry.ops.last()
                pushed("alice", Undo(last.opId, last.op, BandChange(BandPart(), BandPart()), entry.id)).rejected.shouldBeEmpty()
            }
        }

        expect("a record too large for the table keeps its operation without the change") {
            with(HistoryApi()) {
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
                history.bands.getValue("band1").values.all { it.length <= HistoryLog.MAX_RECORD_BYTES } shouldBe true
                val entry = log().entries.single()
                withClue("both operations are listed") { entry.ops.map { it.opId } shouldBe listOf("small", "large") }
                withClue("only the small one can be undone") { entry.changes.keys shouldBe setOf("small") }
            }
        }

        expect("deleting the band deletes its history") {
            with(HistoryApi()) {
                createBand()
                pushed("alice", RenameBand("Gone soon"))
                history.bands.getValue("band1").shouldNotBeEmpty()
                call("DELETE", "/bands/band1", "alice").status shouldBe 204
                withClue("kept while the band waits") { history.bands.getValue("band1").shouldNotBeEmpty() }
                now += com.jacekgajek.kapelusz.model.BandDeletion.DELAY_MS
                call("GET", "/bands/band1/state", "alice").status shouldBe 404
                history.bands["band1"].isNullOrEmpty() shouldBe true
            }
        }

        // ---- dropped oversize changes ----

        expect("a huge batch is logged without its changes and cannot be undone") {
            with(HistoryApi()) {
                createBand()
                // 500 events with long notes: well within the band's size, but their changes are over what is logged.
                val events = (1..Api.MAX_OPS_PER_PUSH).map { i ->
                    CreateEvent(
                        "e$i",
                        "Gig $i".padEnd(100, '.'),
                        LocalDate(2026, 9, 1),
                        location = "l".repeat(500),
                        notes = "n".repeat(2000),
                    )
                }
                val result = pushed("alice", *events.toTypedArray())
                result.accepted.size shouldBe Api.MAX_OPS_PER_PUSH
                KapeluszJson.encodeToString(Band.serializer(), result.band).length shouldBeGreaterThan HistoryLog.MAX_LOGGED_CHANGES
                val entry = log().entries.first()
                withClue("the operations are still listed") { entry.ops.size shouldBe Api.MAX_OPS_PER_PUSH }
                withClue("but not what they changed") { entry.changes.shouldBeEmpty() }

                val undone = pushed("alice", Undo("op1", events.first(), BandChange(BandPart(), BandPart()), entry.id))
                undone.rejected.values.single().reason shouldBe RejectionReason.NOT_UNDOABLE

                // A small push afterwards is logged with its change again.
                pushed("alice", RenameBand("Small"))
                log().entries.first().changes.shouldNotBeEmpty()
            }
        }

        context("undo resolution") {
            expect("undos of malformed or missing entries are refused") {
                with(HistoryApi()) {
                    createBand()
                    pushed("alice", RenameBand("New name")) // op1
                    pushed("alice", RenameBand("Newer name")) // op2, the one undone below
                    val real = log().entries.first { entry -> entry.ops.any { it.opId == "op2" } }
                    val realName = real.id!!
                    val realEntry = HistoryEntry(real.userId, real.memberId, real.at, real.ops, real.changes)
                    // An entry from before changes were recorded, and one listing the operation twice (corrupted).
                    val unrecorded = "2026-09/1790000000001-${UUID.randomUUID()}.json"
                    val unrecordedEntry = realEntry.copy(changes = emptyMap())
                    record("band1", unrecorded, KapeluszJson.encodeToString(HistoryEntry.serializer(), unrecordedEntry))
                    val twice = "2026-09/1790000000002-${UUID.randomUUID()}.json"
                    KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry).let { record("band1", twice, it, it) }
                    // The same entry, but in another band's log: an undo only reads its own band's.
                    createBand(bandId = "band2")
                    val elsewhere = "2026-09/1790000000003-${UUID.randomUUID()}.json"
                    record("band2", elsewhere, KapeluszJson.encodeToString(HistoryEntry.serializer(), realEntry))

                    // An entry that is not JSON any more: its undo is refused, the history around it stays readable.
                    val corrupt = "2026-09/1790000000004-${UUID.randomUUID()}.json"
                    record("band1", corrupt, "{not json")
                    wholeMonth("2026-09").any { entry -> entry.ops.any { it.opId == "op2" } } shouldBe true

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
                        withClue("entry $name") { result.rejected.values.single().reason shouldBe RejectionReason.NOT_UNDOABLE }
                        result.band.name shouldBe "Newer name"
                    }
                    // The real entry still works: the refusals took nothing away.
                    pushed("alice", undo(realName)).band.name shouldBe "New name"
                }
            }

            expect("an undo is permitted by the recorded operation, not the claimed one") {
                with(HistoryApi()) {
                    createBand()
                    pushed("alice", AddMember("m-bob", "Bob"), AddMember("m-vera", "Vera"))
                    link("m-bob", "bob", Role.MEMBER)
                    link("m-vera", "vera", Role.VIEWER)
                    pushed("alice", RenameBand("Alice's name")) // op3: admins only
                    val rename = log().entries.first().id
                    // Bob claims the entry holds his own cost (which he may undo); the server reads the rename from the log.
                    val claimed = Undo(
                        "op3",
                        AddCost("c-bob", "m-bob", Money(100), LocalDate(2026, 9, 1)),
                        BandChange(BandPart(), BandPart()),
                        rename,
                    )
                    pushed("bob", claimed).rejected.values.single().reason shouldBe RejectionReason.ROLE_REQUIRED
                    pushed("vera", undo(rename, "op3")).rejected.values.single().reason shouldBe RejectionReason.ROLE_REQUIRED

                    // Bob's own cost he may undo; Alice's he may not.
                    pushed("bob", AddCost("c-bob", "m-bob", Money(100), LocalDate(2026, 9, 1))) // op6
                    val bobsCost = log().entries.first().id
                    pushed("alice", AddCost("c-alice", "m-alice", Money(100), LocalDate(2026, 9, 1))) // op7
                    val alicesCost = log().entries.first().id
                    pushed("bob", undo(alicesCost, "op7")).rejected.values.single().reason shouldBe RejectionReason.ROLE_REQUIRED
                    val undone = pushed("bob", undo(bobsCost, "op6"))
                    withClue(undone.rejected.toString()) { undone.rejected.shouldBeEmpty() }
                    undone.band.cost("c-bob").shouldBeNull()
                    undone.band.cost("c-alice").shouldNotBeNull()
                }
            }
        }

        // ---- attachments ----

        expect("attachment files follow the roles and archived members lose access") {
            with(HistoryApi()) {
                createBand()
                pushed("alice", AddMember("m-bob", "Bob"), AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)))
                link("m-bob", "bob", Role.MEMBER)
                val photo = Attachment("a1", AttachmentKind.IMAGE, "receipt.jpg", 1000)
                // The operation follows the cost's rules: Bob may not attach to Alice's cost ...
                pushed("bob", AddAttachment("c1", photo)).rejected.values.single().reason shouldBe RejectionReason.ROLE_REQUIRED
                pushed("alice", AddAttachment("c1", photo))
                // ... but any member may upload the file of a listed attachment (the operation was the check).
                fun upload(user: String) = call("POST", "/bands/band1/attachments", user, KapeluszJson.encodeToString(AttachmentUploadRequest.serializer(), AttachmentUploadRequest("a1")))
                upload("bob").status shouldBe 200
                store.files += StorageKeys.attachment("band1", photo)
                call("GET", "/bands/band1/attachments/a1", "bob").status shouldBe 200

                pushed("alice", SetMemberArchived("m-bob", true))
                for (response in listOf(upload("bob"), call("GET", "/bands/band1/attachments/a1", "bob"))) {
                    response.status shouldBe 404
                    response.body!! shouldContain ApiErrors.BAND_NOT_FOUND
                }
            }
        }
    })
