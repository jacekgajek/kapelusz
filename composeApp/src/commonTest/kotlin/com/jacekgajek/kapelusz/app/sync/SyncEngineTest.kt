package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.app.attachments.AttachmentUploads
import com.jacekgajek.kapelusz.app.attachments.PendingUpload
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.bands.BandStore
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.storage.KeyValueStore
import com.jacekgajek.kapelusz.app.testing.FakeServer
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.Undo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest :
    ExpectSpec({
        coroutineTestScope = true
        var tokenCount = 0
        var nextId = 0

        // The counters are shared by the tests of this spec: every test starts them over.
        beforeTest {
            tokenCount = 0
            nextId = 0
        }

        fun setup(server: FakeServer, scope: CoroutineScope): Pair<BandRepository, SyncEngine> {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            val api = ApiClient(server.client, "https://api.test") { refused ->
                if (refused != null) "alice:${++tokenCount}" else "alice:$tokenCount"
            }
            return repository to SyncEngine(api, repository, scope)
        }

        // Zed's band, which Alice joined as a member; on her device.
        suspend fun joinedBand(server: FakeServer, repository: BandRepository, sync: SyncEngine): String {
            server.bands["b1"] = Band.create("b1", "Night Owls", "PLN", "m-zed", "Zed", "zed")
            server.serverSide("b1", AddMember("m-alice", "Alice"), LinkUser("m-alice", "alice", Role.MEMBER))
            repository.load("alice")
            sync.syncNow()
            sync.download("b1")
            return "b1"
        }

        expect("creates pushes and pulls a band made offline") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            val events = mutableListOf<SyncEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sync.events.collect { events += it } }
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob"))
            val bandId = repository.currentBandId!!

            sync.syncNow() shouldBe true
            sync.status.value shouldBe SyncStatus.SYNCED
            server.bands.getValue(bandId).members.map { it.name } shouldBe listOf("Alice", "Bob")
            repository.local(bandId)!!.pending.shouldBeEmpty()
            repository.local(bandId)!!.needsCreate shouldBe false
            testScheduler.advanceUntilIdle()
            withClue("told once, when the server has the band") { events shouldBe listOf<SyncEvent>(SyncEvent.BandCreated(bandId)) }

            server.serverSide(bandId, RenameBand("Renamed elsewhere"))
            sync.syncNow() shouldBe true
            repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.name shouldBe "Renamed elsewhere"
            repository.index.value!!.bands.map { it.name } shouldBe listOf("Renamed elsewhere")
        }

        expect("server refusals are reported and dropped") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            val bandId = repository.currentBandId!!
            sync.syncNow()

            // Alice adds a member locally while, on the server, she has meanwhile become a viewer.
            repository.dispatch(AddMember("carol", "Carol"))
            server.bands[bandId] = server.bands.getValue(bandId).let { band ->
                band.copy(members = band.members.map { if (it.userId == "alice") it.copy(role = Role.VIEWER) else it })
            }
            val refused = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.rejections.first() }
            sync.syncNow()
            refused.await().reason shouldBe RejectionReason.ROLE_REQUIRED
            repository.local(bandId)!!.pending.shouldBeEmpty()
            repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.members.none { it.name == "Carol" } shouldBe true
        }

        expect("retries once with fresh token after 401") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            server.unauthorizedOnce = true
            sync.syncNow() shouldBe true
            server.tokensSeen.take(2) shouldBe listOf("alice:0", "alice:1")
        }

        expect("downloads bands the user already belongs to") {
            val server = FakeServer()
            server.bands["b1"] = Band.create("b1", "Existing", "PLN", "m1", "Alice", "alice")
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            sync.syncNow()
            repository.index.value!!.bands.map { it.name } shouldBe listOf("Existing")
            repository.state.value shouldBe BandState.NoBand

            sync.download("b1")
            repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.name shouldBe "Existing"
        }

        expect("reports offline when the server is unreachable") {
            val offline = HttpClient(MockEngine { throw IllegalStateException("no network") })
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" })
            val sync = SyncEngine(ApiClient(offline, "https://api.test") { "alice:0" }, repository, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            sync.syncNow() shouldBe false
            sync.status.value shouldBe SyncStatus.OFFLINE
            withClue("nothing is lost while offline") { repository.local(repository.currentBandId!!)!!.needsCreate shouldBe true }
        }

        expect("loop recovers after network errors that are not exceptions") {
            val server = FakeServer()
            val repository = BandRepository(InMemoryStore(), backgroundScope, { "id${nextId++}" })
            val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, backgroundScope)
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            server.offline = true

            // The mock HTTP engine answers on a real thread, so wait for status changes rather than stepping time.
            sync.start()
            sync.status.first { it == SyncStatus.OFFLINE }

            server.offline = false
            sync.status.first { it == SyncStatus.SYNCED } // after the backoff, the loop tries again
            repository.local(repository.currentBandId!!)!!.needsCreate shouldBe false
            sync.stop()
        }

        expect("losing access to one band does not block the others") {
            val server = FakeServer()
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" }, clock = { 7 })
            val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Lost Band", "PLN", "Alice")
            val lost = repository.currentBandId!!
            repository.createBand("Kept Band", "PLN", "Alice")
            val kept = repository.currentBandId!!
            sync.syncNow() shouldBe true

            repository.open(lost)
            repository.dispatch(AddMember("bob", "Bob")) // unsent when access is lost
            repository.open(kept)
            repository.dispatch(RenameBand("Kept and renamed"))
            server.bands.remove(lost)

            val message = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }
            sync.syncNow() shouldBe true
            message.await() shouldBe LostBand("Lost Band", deleted = false)
            server.bands.getValue(kept).name shouldBe "Kept and renamed"
            repository.index.value!!.bands.map { it.name } shouldBe listOf("Kept and renamed")
            withClue("unsent changes are kept") {
                store.values.keys.any { it.startsWith(BandStore.bandKey("alice", lost) + "-unsent-") } shouldBe true
            }
        }

        // A change held for its Undo snackbar is not sent, nor anything after it; Undo takes it back.
        expect("a held change waits for its snackbar and undo takes it back") {
            val server = FakeServer()
            var now = 1_000L
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" }, clock = { now })
            val api = ApiClient(server.client, "https://api.test") { "alice:0" }
            val sync = SyncEngine(api, repository, coroutineTestScope, clock = { now })
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            val bandId = repository.currentBandId!!
            sync.syncNow() shouldBe true

            repository.dispatch(RenameBand("Held"), holdFor = 15_000)
            val held = repository.lastOpId!!
            repository.dispatch(AddMember("bob", "Bob")) // made after it: may build on it, so it waits too
            sync.syncNow() shouldBe true
            server.bands.getValue(bandId).name shouldBe "Night Owls"
            server.bands.getValue(bandId).member("bob").shouldBeNull()

            repository.withdraw(bandId, held) shouldBe true
            withClue("shown without it at once") { (repository.state.value as BandState.Ready).band.name shouldBe "Night Owls" }
            sync.syncNow() shouldBe true
            withClue("never sent") { server.bands.getValue(bandId).name shouldBe "Night Owls" }
            withClue("what came after goes once nothing holds it") { server.bands.getValue(bandId).member("bob").shouldNotBeNull() }

            // Released (its snackbar is gone): sent, and no longer taken back.
            repository.dispatch(RenameBand("Kept"), holdFor = 15_000)
            val kept = repository.lastOpId!!
            repository.release(bandId, kept)
            repository.withdraw(bandId, kept) shouldBe false
            sync.syncNow() shouldBe true
            server.bands.getValue(bandId).name shouldBe "Kept"

            // A hold whose time passed (e.g. the app was closed meanwhile) no longer counts, nor can it be taken back.
            repository.dispatch(RenameBand("Expired"), holdFor = 15_000)
            val expired = repository.lastOpId!!
            now += 15_000
            repository.withdraw(bandId, expired) shouldBe false
            sync.syncNow() shouldBe true
            server.bands.getValue(bandId).name shouldBe "Expired"
            withClue("holds of sent changes are dropped") { repository.local(bandId)!!.holds.shouldBeEmpty() }
        }

        // Once sent, a change is never taken back by an Undo, even if the clock then goes back.
        expect("a sent change cannot be withdrawn when the clock goes back") {
            val server = FakeServer()
            var now = 1_000L
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" }, clock = { now })
            val api = ApiClient(server.client, "https://api.test") { "alice:0" }
            val sync = SyncEngine(api, repository, coroutineTestScope, clock = { now })
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            val bandId = repository.currentBandId!!
            sync.syncNow() shouldBe true

            server.offline = true
            repository.dispatch(RenameBand("Sent"), holdFor = 15_000)
            val sent = repository.lastOpId!!
            now += 15_000 // its hold ran out (e.g. the snackbar's timer lagged), so a push takes it
            sync.syncNow() shouldBe false
            now -= 15_000 // the clock is set back: by time alone it would look held again
            withClue("it may have reached the server") { repository.withdraw(bandId, sent) shouldBe false }
            server.offline = false
            sync.syncNow() shouldBe true
            server.bands.getValue(bandId).name shouldBe "Sent"
        }

        // A band the list no longer shows is asked for, and dropped when it is gone, though it is not open.
        expect("a band removed while another is open is found from the list") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Lost Band", "PLN", "Alice")
            val lost = repository.currentBandId!!
            repository.createBand("Kept Band", "PLN", "Alice")
            sync.syncNow() shouldBe true

            server.bands.remove(lost)
            server.deletedBands += lost
            val message = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }
            sync.syncNow()
            message.await() shouldBe LostBand("Lost Band", deleted = true)
            repository.local(lost).shouldBeNull()
            repository.index.value!!.bands.map { it.name } shouldBe listOf("Kept Band")
        }

        // An answer the app cannot read is an error, not "offline".
        expect("an unreadable answer is an error") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            server.unreadableMe = true
            sync.syncNow() shouldBe false
            sync.status.value shouldBe SyncStatus.ERROR
        }

        // A token renewed by a parallel request is used, not renewed once more.
        expect("a token renewed meanwhile is reused") {
            val server = FakeServer()
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" })
            var current = "alice:1"
            val refusals = mutableListOf<String?>()
            val sync = SyncEngine(
                ApiClient(server.client, "https://api.test") { refused ->
                    refusals += refused
                    current.also { if (refused == null) current = "alice:2" } // renewed by a parallel request after this one sent alice:1
                },
                repository,
                coroutineTestScope,
            )
            repository.load("alice")
            server.unauthorizedOnce = true
            sync.syncNow() shouldBe true
            server.tokensSeen.take(2) shouldBe listOf("alice:1", "alice:2")
            withClue("the refused token is named, so the session can tell it was renewed") {
                refusals.take(2) shouldBe listOf(null, "alice:1")
            }
        }

        // The server takes at most 20 undos per push; more go in the next one.
        expect("pushes split before too many undos") {
            fun envelope(n: Int, op: Operation) = OpEnvelope("op$n", "c", n.toLong(), 0, op)
            val undo = Undo("x", RenameBand("x"), BandChange(BandPart(), BandPart()))
            val pending = (0 until 30).map { envelope(it, if (it % 10 == 0) RenameBand("b$it") else undo) }
            val batch = SyncRound.batchOf(pending)
            batch.count { it.op is Undo } shouldBe Limits.MAX_UNDOS_PER_PUSH
            batch shouldBe pending.take(batch.size)
            val plain = (0 until 250).map { envelope(it, RenameBand("b$it")) }
            SyncRound.batchOf(plain) shouldBe plain.take(SyncRound.BATCH_SIZE)
        }

        expect("waits for the user after sign-in is needed") {
            val server = FakeServer()
            var token: String? = null
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" })
            val sync = SyncEngine(ApiClient(server.client, "https://api.test") { token }, repository, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")

            sync.syncNow() shouldBe false
            sync.status.value shouldBe SyncStatus.NEEDS_SIGN_IN
            token = "alice:1"
            withClue("no retries until the user signs in again") { sync.syncNow() shouldBe false }
            sync.status.value shouldBe SyncStatus.NEEDS_SIGN_IN
            server.tokensSeen.shouldBeEmpty()

            sync.resumeAfterSignIn()
            sync.syncNow() shouldBe true
            sync.status.value shouldBe SyncStatus.SYNCED
        }

        expect("a generic 404 never deletes bands") {
            val wrongUrl = HttpClient(MockEngine { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound) })
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" })
            val sync = SyncEngine(ApiClient(wrongUrl, "https://api.test") { "alice:0" }, repository, coroutineTestScope)
            repository.load("alice")
            repository.adoptFromServer(Band.create("b1", "Synced", "PLN", "m1", "Alice", "alice"), "v1")
            sync.syncNow()
            sync.status.value shouldBe SyncStatus.ERROR
            repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.name shouldBe "Synced"
        }

        context("attachment uploads") {
            expect("uploads a file once the server has the attachment") {
                val server = FakeServer()
                val store = InMemoryStore()
                val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" })
                val api = ApiClient(server.client, "https://api.test") { "alice:0" }
                val puts = mutableListOf<Pair<String, String>>()
                val uploads = AttachmentUploads(store, api, repository) { url, _, base64 ->
                    puts += url to base64
                    200
                }
                val sync = SyncEngine(api, repository, coroutineTestScope, uploads = uploads)
                repository.load("alice")
                uploads.load("alice")
                repository.createBand("Night Owls", "PLN", "Alice")
                val bandId = repository.currentBandId!!
                val me = (repository.state.value as BandState.Ready).me!!.id
                repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1))).shouldBeNull()
                repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4))).shouldBeNull()
                uploads.enqueue(PendingUpload(bandId, "a1"), "AAAA")
                uploads.published("a1")
                // Also queued: a file whose attachment is not on the cost (refused or removed meanwhile): dropped, not uploaded.
                uploads.enqueue(PendingUpload(bandId, "gone"), "BBBB")
                uploads.published("gone")

                sync.syncNow() shouldBe true
                puts shouldBe listOf("https://s3.test/a1" to "AAAA")
                uploads.pending.value.shouldBeEmpty()
                store.read(AttachmentUploads.fileKey("alice", "a1")).shouldBeNull()
                store.read(AttachmentUploads.fileKey("alice", "gone")).shouldBeNull()
            }

            expect("keeps the file while the upload fails") {
                val server = FakeServer()
                val store = InMemoryStore()
                val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" })
                val api = ApiClient(server.client, "https://api.test") { "alice:0" }
                var status = 500
                val uploads = AttachmentUploads(store, api, repository) { _, _, _ -> status }
                val sync = SyncEngine(api, repository, coroutineTestScope, uploads = uploads)
                repository.load("alice")
                uploads.load("alice")
                repository.createBand("Night Owls", "PLN", "Alice")
                val me = (repository.state.value as BandState.Ready).me!!.id
                repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1)))
                repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.PDF, "invoice.pdf", 4)))
                uploads.enqueue(PendingUpload(repository.currentBandId!!, "a1"), "AAAA")
                uploads.published("a1")

                sync.syncNow()
                uploads.pending.value.map { it.attachmentId } shouldBe listOf("a1")
                // A later sync retries; the band itself has nothing left to push, but its upload still brings it in.
                status = 200
                sync.syncNow()
                uploads.pending.value.shouldBeEmpty()
            }

            expect("keeps the file when the server does not know the route") {
                // An older server (no attachment endpoints) answers with a generic 404: the only copy must survive.
                val server = FakeServer()
                server.attachmentsRoute = false
                val store = InMemoryStore()
                val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" })
                val api = ApiClient(server.client, "https://api.test") { "alice:0" }
                val uploads = AttachmentUploads(store, api, repository) { _, _, _ -> 200 }
                val sync = SyncEngine(api, repository, coroutineTestScope, uploads = uploads)
                repository.load("alice")
                uploads.load("alice")
                repository.createBand("Night Owls", "PLN", "Alice")
                val me = (repository.state.value as BandState.Ready).me!!.id
                repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1)))
                repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4)))
                uploads.enqueue(PendingUpload(repository.currentBandId!!, "a1"), "AAAA")
                uploads.published("a1")

                sync.syncNow()
                uploads.pending.value.map { it.attachmentId } shouldBe listOf("a1")
                store.read(AttachmentUploads.fileKey("alice", "a1")) shouldBe "AAAA"
            }
        }

        expect("a deleted band is reported as deleted") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            val bandId = repository.currentBandId!!
            sync.syncNow() shouldBe true

            server.bands.remove(bandId)
            server.deletedBands += bandId
            val message = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }
            sync.syncNow()
            message.await() shouldBe LostBand("Night Owls", deleted = true)
            repository.local(bandId).shouldBeNull()
        }

        context("leaving a band") {
            expect("sends unsent changes first and forgets the band quietly") {
                val server = FakeServer()
                val (repository, sync) = setup(server, coroutineTestScope)
                val bandId = joinedBand(server, repository, sync)
                repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
                val lost = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }

                sync.leave(bandId)
                withClue("the change went first") { server.bands.getValue(bandId).costs.size shouldBe 1 }
                server.bands.getValue(bandId).member("m-alice")!!.userId.shouldBeNull()
                repository.local(bandId).shouldBeNull()
                sync.syncNow() shouldBe true
                withClue("leaving on purpose is not reported as lost access") { lost.isActive shouldBe true }
            }

            expect("a leave whose answer was lost counts when tried again") {
                val server = FakeServer()
                val (repository, sync) = setup(server, coroutineTestScope)
                val bandId = joinedBand(server, repository, sync)
                server.loseLeaveAnswer = true
                shouldThrowAny { sync.leave(bandId) }
                withClue("not known to be done yet") { repository.local(bandId).shouldNotBeNull() }

                sync.leave(bandId)
                repository.local(bandId).shouldBeNull()
                server.leaves shouldBe listOf("alice")
            }

            expect("a band with refused changes cannot be left") {
                val server = FakeServer()
                val (repository, sync) = setup(server, coroutineTestScope)
                val bandId = joinedBand(server, repository, sync)
                server.refusePushes += bandId
                repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
                shouldThrow<LeaveBlocked> { sync.leave(bandId) }.stuck shouldBe true
                server.leaves.shouldBeEmpty()
                repository.local(bandId).shouldNotBeNull()
            }
        }

        expect("a round shows syncing only when it sends something") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            joinedBand(server, repository, sync)
            // Every status as it is set: an unconfined collector runs at once, inside the setter, so a short "syncing"
            // isn't missed (a StateFlow keeps only the latest value for a collector that comes later; it failed on CI).
            val seen = mutableListOf<SyncStatus>()
            backgroundScope.launch(Dispatchers.Unconfined) { sync.status.collect { seen += it } }

            sync.syncNow()
            withClue("a check for news keeps the icon") { seen.toList() shouldNotContain SyncStatus.SYNCING }

            repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
            sync.syncNow()
            withClue("sending shows it") { seen.toList() shouldContain SyncStatus.SYNCING }
        }

        expect("with messages the app looks for news every five minutes") {
            val (_, sync) = setup(FakeServer(), coroutineTestScope)
            sync.waitAfter(ok = true, backoff = 0) shouldBe 30_000
            sync.messaged(true)
            sync.waitAfter(ok = true, backoff = 0) shouldBe SyncEngine.MESSAGED_INTERVAL
            withClue("a failed round still retries soon") { sync.waitAfter(ok = false, backoff = 5_000) shouldBe 5_000 }
        }

        expect("a message about another band fetches it quietly") {
            val server = FakeServer()
            val (repository, sync) = setup(server, backgroundScope)
            val other = joinedBand(server, repository, sync)
            repository.createBand("Own band", "PLN", "Alice")
            sync.syncNow() shouldBe true
            server.serverSide(other, RenameBand("Renamed by Zed"))

            sync.bandChanged(other)
            withClue("the loop isn't running: ignored") { repository.local(other)!!.base.name shouldBe "Night Owls" }

            sync.start()
            sync.bandChanged(other)
            testScheduler.runCurrent()
            withClue("the other band is fetched") { repository.local(other)?.base?.name shouldBe "Renamed by Zed" }
            withClue("and the open band stays open") {
                repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.name shouldBe "Own band"
            }
            sync.stop()
        }

        context("a hidden tab") {
            expect("sends what waits then stops") {
                val server = FakeServer()
                val (repository, sync) = setup(server, backgroundScope)
                val bandId = joinedBand(server, repository, sync)
                sync.start()
                repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
                repository.release(bandId, *repository.local(bandId)!!.pending.map { it.opId }.toTypedArray())
                sync.pauseAfterSending()
                testScheduler.runCurrent()
                withClue("the change went out") { server.bands.getValue(bandId).costs.size shouldBe 1 }
                repository.local(bandId)!!.pending.shouldBeEmpty()
                // The drain is over once nothing waits: a minute later nothing has been asked, and nothing in the next hour.
                testScheduler.advanceTimeBy(60_000)
                testScheduler.runCurrent()
                val requests = server.requests
                testScheduler.advanceTimeBy(3_600_000)
                testScheduler.runCurrent()
                withClue("then nothing more while hidden") { server.requests shouldBe requests }
            }

            expect("does not keep retrying changes the server refuses") {
                val server = FakeServer()
                val (repository, sync) = setup(server, backgroundScope)
                val bandId = joinedBand(server, repository, sync)
                server.refusePushes += bandId
                repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
                repository.release(bandId, *repository.local(bandId)!!.pending.map { it.opId }.toTypedArray())
                sync.pauseAfterSending()
                testScheduler.runCurrent()
                withClue("the push was tried, and refused") { sync.stuck.value shouldContainKey bandId }
                testScheduler.advanceTimeBy(60_000)
                testScheduler.runCurrent()
                val requests = server.requests
                testScheduler.advanceTimeBy(3_600_000)
                testScheduler.runCurrent()
                withClue("stuck: nothing more while hidden") { server.requests shouldBe requests }
            }
        }

        expect("an outdated app stops syncing and says so") {
            val server = FakeServer()
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, { "id${nextId++}" })
            val api = ApiClient(server.client, "https://api.test", appVersion = 11) { "alice:0" }
            val signals = mutableListOf<ApiSignal>()
            // Delivered at once where it is emitted (the engine's thread): the loop's own dispatcher never gets a turn here.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { api.signals.collect { signals += it } }
            val sync = SyncEngine(api, repository, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            sync.syncNow() shouldBe true
            withClue("every request carries the app version") { server.appVersionsSeen.forAll { it shouldBe "11" } }

            server.updateRequired = true
            sync.syncNow() shouldBe false
            testScheduler.advanceUntilIdle()
            signals shouldBe listOf<ApiSignal>(ApiSignal.UpdateRequired)
            sync.status.value shouldBe SyncStatus.UPDATE_REQUIRED
            val before = server.requests
            sync.syncNow() shouldBe false
            withClue("no pointless retries until the app is updated") { server.requests shouldBe before }
        }

        expect("the background sync keeps every band without switching the open one") {
            val server = FakeServer()
            val (repository, sync) = setup(server, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Open band", "PLN", "Alice")
            val open = repository.currentBandId!!
            sync.syncNow() shouldBe true
            // Alice joined another band elsewhere (the web, another phone).
            server.bands["elsewhere"] = Band.create("elsewhere", "Joined elsewhere", "PLN", "m-a", "Alice", "alice")

            sync.syncAll() shouldBe true
            withClue("downloaded") { repository.local("elsewhere")?.base?.name shouldBe "Joined elsewhere" }
            withClue("the open band stays open") { repository.currentBandId shouldBe open }
            repository.allBands().map { it.name }.toSet() shouldBe setOf("Open band", "Joined elsewhere")
        }

        // A push the server always refuses is reported, not retried, and can be discarded.
        expect("a push refused as a whole is stuck until discarded") {
            val server = FakeServer()
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" }, clock = { 7 })
            val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, coroutineTestScope)
            repository.load("alice")
            repository.createBand("Stuck Band", "PLN", "Alice")
            val stuck = repository.currentBandId!!
            repository.createBand("Other Band", "PLN", "Alice")
            val other = repository.currentBandId!!
            sync.syncNow() shouldBe true

            server.refusePushes += stuck
            repository.open(stuck)
            repository.dispatch(RenameBand("Never sent"))
            repository.open(other)
            repository.dispatch(RenameBand("Other renamed"))
            sync.syncNow() shouldBe true
            sync.stuck.value[stuck] shouldBe StuckPush(400, "too_many_devices", "Too many devices")
            withClue("other bands still sync") { server.bands.getValue(other).name shouldBe "Other renamed" }
            sync.stuck.value.keys shouldBe setOf(stuck)
            withClue("not in sync, though the stuck band is not the open one") { sync.status.value shouldBe SyncStatus.ERROR }

            // Not sent again in later rounds, while the band is still pulled.
            repository.open(stuck)
            server.serverSide(stuck, AddMember("bob", "Bob"))
            server.pushedBands.clear()
            sync.syncNow() shouldBe true
            server.pushedBands.shouldBeEmpty()
            repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.members.any { it.name == "Bob" } shouldBe true
            val exported = repository.pendingAsJson(stuck)
            exported shouldContain repository.local(stuck)!!.pending.single().opId
            exported shouldContain "Never sent"

            sync.discardStuck(stuck)
            sync.stuck.value.shouldBeEmpty()
            repository.local(stuck)!!.pending.shouldBeEmpty()
            repository.state.value.shouldBeInstanceOf<BandState.Ready>().band.name shouldBe "Stuck Band"
            withClue("a backup is kept") {
                store.values.keys.any { it.startsWith(BandStore.bandKey("alice", stuck) + "-unsent-") } shouldBe true
            }

            server.refusePushes.clear()
            repository.dispatch(RenameBand("Sent at last"))
            sync.syncNow() shouldBe true
            server.bands.getValue(stuck).name shouldBe "Sent at last"
            repository.local(stuck)!!.pending.shouldBeEmpty()
            sync.status.value shouldBe SyncStatus.SYNCED
        }

        context("discarding") {
            // The discard dialog promises a copy: the files of discarded attachments stay on the device, unqueued.
            expect("keeps the files of unsent attachments") {
                val server = FakeServer()
                val store = InMemoryStore()
                val repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" })
                val api = ApiClient(server.client, "https://api.test") { "alice:0" }
                val puts = mutableListOf<String>()
                val uploads = AttachmentUploads(store, api, repository) { url, _, _ ->
                    puts += url
                    200
                }
                val sync = SyncEngine(api, repository, coroutineTestScope, uploads = uploads)
                repository.load("alice")
                uploads.load("alice")
                repository.createBand("Night Owls", "PLN", "Alice")
                val bandId = repository.currentBandId!!
                sync.syncNow() shouldBe true

                server.refusePushes += bandId
                val me = (repository.state.value as BandState.Ready).me!!.id
                repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1))).shouldBeNull()
                repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4))).shouldBeNull()
                uploads.enqueue(PendingUpload(bandId, "a1"), "AAAA")
                uploads.published("a1")
                sync.syncNow() shouldBe true
                sync.stuck.value.keys shouldBe setOf(bandId)

                sync.discardStuck(bandId)
                server.refusePushes.clear()
                sync.syncNow() shouldBe true
                withClue("the file is kept") { store.read(AttachmentUploads.fileKey("alice", "a1")) shouldBe "AAAA" }
                withClue("but no longer waits for an upload") { uploads.pending.value.shouldBeEmpty() }
                puts.shouldBeEmpty()
            }

            // A change made while the backup of the discarded ones is written is not discarded with them.
            expect("keeps a change made during the backup") {
                lateinit var repository: BandRepository
                val store = object : KeyValueStore {
                    val values = mutableMapOf<String, String>()
                    override suspend fun read(key: String) = values[key]
                    override suspend fun write(key: String, value: String) {
                        if ("-unsent-" in key) repository.dispatch(RenameBand("Made meanwhile")).shouldBeNull()
                        values[key] = value
                    }
                    override suspend fun remove(key: String) {
                        values.remove(key)
                    }
                }
                val server = FakeServer()
                repository = BandRepository(store, coroutineTestScope, { "id${nextId++}" })
                val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, coroutineTestScope)
                repository.load("alice")
                repository.createBand("Night Owls", "PLN", "Alice")
                val bandId = repository.currentBandId!!
                sync.syncNow() shouldBe true
                server.refusePushes += bandId
                repository.dispatch(RenameBand("Refused"))
                sync.syncNow() shouldBe true

                sync.discardStuck(bandId)
                repository.local(bandId)!!.pending.map { it.op } shouldBe listOf(RenameBand("Made meanwhile"))
                server.refusePushes.clear()
                sync.syncNow() shouldBe true
                server.bands.getValue(bandId).name shouldBe "Made meanwhile"
            }
        }
    })
