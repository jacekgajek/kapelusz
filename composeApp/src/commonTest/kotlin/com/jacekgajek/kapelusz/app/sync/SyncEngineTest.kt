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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class SyncEngineTest {
    private var tokenCount = 0
    private var nextId = 0

    private fun setup(server: FakeServer, scope: CoroutineScope): Pair<BandRepository, SyncEngine> {
        val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { refused -> if (refused != null) "alice:${++tokenCount}" else "alice:$tokenCount" }
        return repository to SyncEngine(api, repository, scope)
    }

    @Test
    fun createsPushesAndPullsABandMadeOffline() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        val events = mutableListOf<SyncEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sync.events.collect { events += it } }
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        repository.dispatch(AddMember("bob", "Bob"))
        val bandId = repository.currentBandId!!

        assertTrue(sync.syncNow())
        assertEquals(SyncStatus.SYNCED, sync.status.value)
        assertEquals(listOf("Alice", "Bob"), server.bands.getValue(bandId).members.map { it.name })
        assertEquals(emptyList(), repository.local(bandId)!!.pending)
        assertEquals(false, repository.local(bandId)!!.needsCreate)
        advanceUntilIdle()
        assertEquals<List<SyncEvent>>(listOf(SyncEvent.BandCreated(bandId)), events, "told once, when the server has the band")

        server.serverSide(bandId, RenameBand("Renamed elsewhere"))
        assertTrue(sync.syncNow())
        assertEquals("Renamed elsewhere", assertIs<BandState.Ready>(repository.state.value).band.name)
        assertEquals(listOf("Renamed elsewhere"), repository.index.value!!.bands.map { it.name })
    }

    @Test
    fun serverRefusalsAreReportedAndDropped() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
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
        assertEquals(RejectionReason.ROLE_REQUIRED, refused.await().reason)
        assertEquals(emptyList(), repository.local(bandId)!!.pending)
        assertTrue(assertIs<BandState.Ready>(repository.state.value).band.members.none { it.name == "Carol" })
    }

    @Test
    fun retriesOnceWithFreshTokenAfter401() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        server.unauthorizedOnce = true
        assertTrue(sync.syncNow())
        assertEquals(listOf("alice:0", "alice:1"), server.tokensSeen.take(2))
    }

    @Test
    fun downloadsBandsTheUserAlreadyBelongsTo() = runTest {
        val server = FakeServer()
        server.bands["b1"] = Band.create("b1", "Existing", "PLN", "m1", "Alice", "alice")
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        sync.syncNow()
        assertEquals(listOf("Existing"), repository.index.value!!.bands.map { it.name })
        assertEquals(BandState.NoBand, repository.state.value)

        sync.download("b1")
        assertEquals("Existing", assertIs<BandState.Ready>(repository.state.value).band.name)
    }

    @Test
    fun reportsOfflineWhenTheServerIsUnreachable() = runTest {
        val offline = HttpClient(MockEngine { throw IllegalStateException("no network") })
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        val sync = SyncEngine(ApiClient(offline, "https://api.test") { "alice:0" }, repository, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        assertEquals(false, sync.syncNow())
        assertEquals(SyncStatus.OFFLINE, sync.status.value)
        assertTrue(repository.local(repository.currentBandId!!)!!.needsCreate, "nothing is lost while offline")
    }

    @Test
    fun loopRecoversAfterNetworkErrorsThatAreNotExceptions() = runTest {
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
        assertEquals(false, repository.local(repository.currentBandId!!)!!.needsCreate)
        sync.stop()
    }

    @Test
    fun losingAccessToOneBandDoesNotBlockTheOthers() = runTest {
        val server = FakeServer()
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" }, clock = { 7 })
        val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, this)
        repository.load("alice")
        repository.createBand("Lost Band", "PLN", "Alice")
        val lost = repository.currentBandId!!
        repository.createBand("Kept Band", "PLN", "Alice")
        val kept = repository.currentBandId!!
        assertTrue(sync.syncNow())

        repository.open(lost)
        repository.dispatch(AddMember("bob", "Bob")) // unsent when access is lost
        repository.open(kept)
        repository.dispatch(RenameBand("Kept and renamed"))
        server.bands.remove(lost)

        val message = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }
        assertTrue(sync.syncNow())
        assertEquals(LostBand("Lost Band", deleted = false), message.await())
        assertEquals("Kept and renamed", server.bands.getValue(kept).name)
        assertEquals(listOf("Kept and renamed"), repository.index.value!!.bands.map { it.name })
        assertTrue(store.values.keys.any { it.startsWith(BandStore.bandKey("alice", lost) + "-unsent-") }, "unsent changes are kept")
    }

    /** A change held for its Undo snackbar is not sent, nor anything after it; Undo takes it back. */
    @Test
    fun aHeldChangeWaitsForItsSnackbarAndUndoTakesItBack() = runTest {
        val server = FakeServer()
        var now = 1_000L
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" }, clock = { now })
        val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, this, clock = { now })
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        assertTrue(sync.syncNow())

        repository.dispatch(RenameBand("Held"), holdFor = 15_000)
        val held = repository.lastOpId!!
        repository.dispatch(AddMember("bob", "Bob")) // made after it: may build on it, so it waits too
        assertTrue(sync.syncNow())
        assertEquals("Night Owls", server.bands.getValue(bandId).name)
        assertEquals(null, server.bands.getValue(bandId).member("bob"))

        assertTrue(repository.withdraw(bandId, held))
        assertEquals("Night Owls", (repository.state.value as BandState.Ready).band.name, "shown without it at once")
        assertTrue(sync.syncNow())
        assertEquals("Night Owls", server.bands.getValue(bandId).name, "never sent")
        assertTrue(server.bands.getValue(bandId).member("bob") != null, "what came after goes once nothing holds it")

        // Released (its snackbar is gone): sent, and no longer taken back.
        repository.dispatch(RenameBand("Kept"), holdFor = 15_000)
        val kept = repository.lastOpId!!
        repository.release(bandId, kept)
        assertFalse(repository.withdraw(bandId, kept))
        assertTrue(sync.syncNow())
        assertEquals("Kept", server.bands.getValue(bandId).name)

        // A hold whose time passed (e.g. the app was closed meanwhile) no longer counts, nor can it be taken back.
        repository.dispatch(RenameBand("Expired"), holdFor = 15_000)
        val expired = repository.lastOpId!!
        now += 15_000
        assertFalse(repository.withdraw(bandId, expired))
        assertTrue(sync.syncNow())
        assertEquals("Expired", server.bands.getValue(bandId).name)
        assertTrue(repository.local(bandId)!!.holds.isEmpty(), "holds of sent changes are dropped")
    }

    /** Once sent, a change is never taken back by an Undo, even if the clock then goes back. */
    @Test
    fun aSentChangeCannotBeWithdrawnWhenTheClockGoesBack() = runTest {
        val server = FakeServer()
        var now = 1_000L
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" }, clock = { now })
        val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, this, clock = { now })
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        assertTrue(sync.syncNow())

        server.offline = true
        repository.dispatch(RenameBand("Sent"), holdFor = 15_000)
        val sent = repository.lastOpId!!
        now += 15_000 // its hold ran out (e.g. the snackbar's timer lagged), so a push takes it
        assertFalse(sync.syncNow())
        now -= 15_000 // the clock is set back: by time alone it would look held again
        assertFalse(repository.withdraw(bandId, sent), "it may have reached the server")
        server.offline = false
        assertTrue(sync.syncNow())
        assertEquals("Sent", server.bands.getValue(bandId).name)
    }

    /** A band the list no longer shows is asked for, and dropped when it is gone, though it is not open. */
    @Test
    fun aBandRemovedWhileAnotherIsOpenIsFoundFromTheList() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        repository.createBand("Lost Band", "PLN", "Alice")
        val lost = repository.currentBandId!!
        repository.createBand("Kept Band", "PLN", "Alice")
        assertTrue(sync.syncNow())

        server.bands.remove(lost)
        server.deletedBands += lost
        val message = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }
        sync.syncNow()
        assertEquals(LostBand("Lost Band", deleted = true), message.await())
        assertEquals(null, repository.local(lost))
        assertEquals(listOf("Kept Band"), repository.index.value!!.bands.map { it.name })
    }

    /** An answer the app cannot read is an error, not "offline". */
    @Test
    fun anUnreadableAnswerIsAnError() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        server.unreadableMe = true
        assertFalse(sync.syncNow())
        assertEquals(SyncStatus.ERROR, sync.status.value)
    }

    /** A token renewed by a parallel request is used, not renewed once more. */
    @Test
    fun aTokenRenewedMeanwhileIsReused() = runTest {
        val server = FakeServer()
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        var current = "alice:1"
        val refusals = mutableListOf<String?>()
        val sync = SyncEngine(
            ApiClient(server.client, "https://api.test") { refused ->
                refusals += refused
                current.also { if (refused == null) current = "alice:2" } // renewed by a parallel request after this one sent alice:1
            },
            repository,
            this,
        )
        repository.load("alice")
        server.unauthorizedOnce = true
        assertTrue(sync.syncNow())
        assertEquals(listOf("alice:1", "alice:2"), server.tokensSeen.take(2))
        assertEquals(listOf(null, "alice:1"), refusals.take(2), "the refused token is named, so the session can tell it was renewed")
    }

    /** The server takes at most 20 undos per push; more go in the next one. */
    @Test
    fun pushesSplitBeforeTooManyUndos() {
        fun envelope(n: Int, op: Operation) = OpEnvelope("op$n", "c", n.toLong(), 0, op)
        val undo = Undo("x", RenameBand("x"), BandChange(BandPart(), BandPart()))
        val pending = (0 until 30).map { envelope(it, if (it % 10 == 0) RenameBand("b$it") else undo) }
        val batch = SyncRound.batchOf(pending)
        assertEquals(Limits.MAX_UNDOS_PER_PUSH, batch.count { it.op is Undo })
        assertEquals(pending.take(batch.size), batch)
        val plain = (0 until 250).map { envelope(it, RenameBand("b$it")) }
        assertEquals(plain.take(SyncRound.BATCH_SIZE), SyncRound.batchOf(plain))
    }

    @Test
    fun waitsForTheUserAfterSignInIsNeeded() = runTest {
        val server = FakeServer()
        var token: String? = null
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        val sync = SyncEngine(ApiClient(server.client, "https://api.test") { token }, repository, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")

        assertEquals(false, sync.syncNow())
        assertEquals(SyncStatus.NEEDS_SIGN_IN, sync.status.value)
        token = "alice:1"
        assertEquals(false, sync.syncNow(), "no retries until the user signs in again")
        assertEquals(SyncStatus.NEEDS_SIGN_IN, sync.status.value)
        assertTrue(server.tokensSeen.isEmpty())

        sync.resumeAfterSignIn()
        assertTrue(sync.syncNow())
        assertEquals(SyncStatus.SYNCED, sync.status.value)
    }

    @Test
    fun aGeneric404NeverDeletesBands() = runTest {
        val wrongUrl = HttpClient(MockEngine { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound) })
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        val sync = SyncEngine(ApiClient(wrongUrl, "https://api.test") { "alice:0" }, repository, this)
        repository.load("alice")
        repository.adoptFromServer(Band.create("b1", "Synced", "PLN", "m1", "Alice", "alice"), "v1")
        sync.syncNow()
        assertEquals(SyncStatus.ERROR, sync.status.value)
        assertEquals("Synced", assertIs<BandState.Ready>(repository.state.value).band.name)
    }

    @Test
    fun uploadsAnAttachmentFileOnceTheServerHasTheAttachment() = runTest {
        val server = FakeServer()
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { "alice:0" }
        val puts = mutableListOf<Pair<String, String>>()
        val uploads = AttachmentUploads(store, api, repository) { url, _, base64 ->
            puts += url to base64
            200
        }
        val sync = SyncEngine(api, repository, this, uploads = uploads)
        repository.load("alice")
        uploads.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        val me = (repository.state.value as BandState.Ready).me!!.id
        assertEquals(null, repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1))))
        assertEquals(null, repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4))))
        uploads.enqueue(PendingUpload(bandId, "a1"), "AAAA")
        uploads.published("a1")
        // Also queued: a file whose attachment is not on the cost (refused or removed meanwhile): dropped, not uploaded.
        uploads.enqueue(PendingUpload(bandId, "gone"), "BBBB")
        uploads.published("gone")

        assertTrue(sync.syncNow())
        assertEquals(listOf("https://s3.test/a1" to "AAAA"), puts)
        assertTrue(uploads.pending.value.isEmpty())
        assertEquals(null, store.read(AttachmentUploads.fileKey("alice", "a1")))
        assertEquals(null, store.read(AttachmentUploads.fileKey("alice", "gone")))
    }

    @Test
    fun keepsTheFileWhileTheUploadFails() = runTest {
        val server = FakeServer()
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { "alice:0" }
        var status = 500
        val uploads = AttachmentUploads(store, api, repository) { _, _, _ -> status }
        val sync = SyncEngine(api, repository, this, uploads = uploads)
        repository.load("alice")
        uploads.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val me = (repository.state.value as BandState.Ready).me!!.id
        repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1)))
        repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.PDF, "invoice.pdf", 4)))
        uploads.enqueue(PendingUpload(repository.currentBandId!!, "a1"), "AAAA")
        uploads.published("a1")

        sync.syncNow()
        assertEquals(listOf("a1"), uploads.pending.value.map { it.attachmentId })
        // A later sync retries; the band itself has nothing left to push, but its upload still brings it in.
        status = 200
        sync.syncNow()
        assertTrue(uploads.pending.value.isEmpty())
    }

    @Test
    fun keepsTheFileWhenTheServerDoesNotKnowTheRoute() = runTest {
        // An older server (no attachment endpoints) answers with a generic 404: the only copy must survive.
        val server = FakeServer()
        server.attachmentsRoute = false
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { "alice:0" }
        val uploads = AttachmentUploads(store, api, repository) { _, _, _ -> 200 }
        val sync = SyncEngine(api, repository, this, uploads = uploads)
        repository.load("alice")
        uploads.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val me = (repository.state.value as BandState.Ready).me!!.id
        repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1)))
        repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4)))
        uploads.enqueue(PendingUpload(repository.currentBandId!!, "a1"), "AAAA")
        uploads.published("a1")

        sync.syncNow()
        assertEquals(listOf("a1"), uploads.pending.value.map { it.attachmentId })
        assertEquals("AAAA", store.read(AttachmentUploads.fileKey("alice", "a1")))
    }

    @Test
    fun aDeletedBandIsReportedAsDeleted() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        assertTrue(sync.syncNow())

        server.bands.remove(bandId)
        server.deletedBands += bandId
        val message = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }
        sync.syncNow()
        assertEquals(LostBand("Night Owls", deleted = true), message.await())
        assertEquals(null, repository.local(bandId))
    }

    /** Zed's band, which Alice joined as a member; on her device. */
    private suspend fun joinedBand(server: FakeServer, repository: BandRepository, sync: SyncEngine): String {
        server.bands["b1"] = Band.create("b1", "Night Owls", "PLN", "m-zed", "Zed", "zed")
        server.serverSide("b1", AddMember("m-alice", "Alice"), LinkUser("m-alice", "alice", Role.MEMBER))
        repository.load("alice")
        sync.syncNow()
        sync.download("b1")
        return "b1"
    }

    @Test
    fun leavingSendsUnsentChangesFirstAndForgetsTheBandQuietly() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        val bandId = joinedBand(server, repository, sync)
        repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
        val lost = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { sync.lostAccess.first() }

        sync.leave(bandId)
        assertEquals(1, server.bands.getValue(bandId).costs.size, "the change went first")
        assertNull(server.bands.getValue(bandId).member("m-alice")!!.userId)
        assertNull(repository.local(bandId))
        assertTrue(sync.syncNow())
        assertTrue(lost.isActive, "leaving on purpose is not reported as lost access")
    }

    @Test
    fun aLeaveWhoseAnswerWasLostCountsWhenTriedAgain() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        val bandId = joinedBand(server, repository, sync)
        server.loseLeaveAnswer = true
        assertFails { sync.leave(bandId) }
        assertTrue(repository.local(bandId) != null, "not known to be done yet")

        sync.leave(bandId)
        assertNull(repository.local(bandId))
        assertEquals(listOf("alice"), server.leaves)
    }

    @Test
    fun aBandWithRefusedChangesCannotBeLeft() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        val bandId = joinedBand(server, repository, sync)
        server.refusePushes += bandId
        repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
        assertTrue(assertFailsWith<LeaveBlocked> { sync.leave(bandId) }.stuck)
        assertTrue(server.leaves.isEmpty())
        assertTrue(repository.local(bandId) != null)
    }

    @Test
    fun aRoundShowsSyncingOnlyWhenItSendsSomething() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        joinedBand(server, repository, sync)
        // Every status as it is set: an unconfined collector runs at once, inside the setter, so a short "syncing"
        // isn't missed (a StateFlow keeps only the latest value for a collector that comes later; it failed on CI).
        val seen = mutableListOf<SyncStatus>()
        backgroundScope.launch(Dispatchers.Unconfined) { sync.status.collect { seen += it } }

        sync.syncNow()
        assertTrue(SyncStatus.SYNCING !in seen.toList(), "a check for news keeps the icon")

        repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
        sync.syncNow()
        assertTrue(SyncStatus.SYNCING in seen.toList(), "sending shows it")
    }

    @Test
    fun withMessagesTheAppLooksForNewsEveryFiveMinutes() = runTest {
        val (_, sync) = setup(FakeServer(), this)
        assertEquals(30_000, sync.waitAfter(ok = true, backoff = 0))
        sync.messaged(true)
        assertEquals(SyncEngine.MESSAGED_INTERVAL, sync.waitAfter(ok = true, backoff = 0))
        assertEquals(5_000, sync.waitAfter(ok = false, backoff = 5_000), "a failed round still retries soon")
    }

    @Test
    fun aMessageAboutAnotherBandFetchesItQuietly() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, backgroundScope)
        val other = joinedBand(server, repository, sync)
        repository.createBand("Own band", "PLN", "Alice")
        assertTrue(sync.syncNow())
        server.serverSide(other, RenameBand("Renamed by Zed"))

        sync.bandChanged(other)
        assertEquals("Night Owls", repository.local(other)!!.base.name, "the loop isn't running: ignored")

        sync.start()
        sync.bandChanged(other)
        runCurrent()
        assertEquals("Renamed by Zed", repository.local(other)?.base?.name, "the other band is fetched")
        assertEquals("Own band", assertIs<BandState.Ready>(repository.state.value).band.name, "and the open band stays open")
        sync.stop()
    }

    @Test
    fun aHiddenTabSendsWhatWaitsThenStops() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, backgroundScope)
        val bandId = joinedBand(server, repository, sync)
        sync.start()
        repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
        repository.release(bandId, *repository.local(bandId)!!.pending.map { it.opId }.toTypedArray())
        sync.pauseAfterSending()
        runCurrent()
        assertEquals(1, server.bands.getValue(bandId).costs.size, "the change went out")
        assertTrue(repository.local(bandId)!!.pending.isEmpty())
        // The drain is over once nothing waits: a minute later nothing has been asked, and nothing in the next hour.
        advanceTimeBy(60_000)
        runCurrent()
        val requests = server.requests
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(requests, server.requests, "then nothing more while hidden")
    }

    @Test
    fun aHiddenTabDoesNotKeepRetryingChangesTheServerRefuses() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, backgroundScope)
        val bandId = joinedBand(server, repository, sync)
        server.refusePushes += bandId
        repository.dispatch(AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 10, 1)))
        repository.release(bandId, *repository.local(bandId)!!.pending.map { it.opId }.toTypedArray())
        sync.pauseAfterSending()
        runCurrent()
        assertTrue(bandId in sync.stuck.value, "the push was tried, and refused")
        advanceTimeBy(60_000)
        runCurrent()
        val requests = server.requests
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(requests, server.requests, "stuck: nothing more while hidden")
    }

    @Test
    fun anOutdatedAppStopsSyncingAndSaysSo() = runTest {
        val server = FakeServer()
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test", appVersion = 11) { "alice:0" }
        val signals = mutableListOf<ApiSignal>()
        // Delivered at once where it is emitted (the engine's thread): the loop's own dispatcher never gets a turn here.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { api.signals.collect { signals += it } }
        val sync = SyncEngine(api, repository, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        assertTrue(sync.syncNow())
        assertTrue(server.appVersionsSeen.all { it == "11" }, "every request carries the app version")

        server.updateRequired = true
        assertFalse(sync.syncNow())
        advanceUntilIdle()
        assertEquals<List<ApiSignal>>(listOf(ApiSignal.UpdateRequired), signals)
        assertEquals(SyncStatus.UPDATE_REQUIRED, sync.status.value)
        val before = server.requests
        assertFalse(sync.syncNow())
        assertEquals(before, server.requests, "no pointless retries until the app is updated")
    }

    @Test
    fun theBackgroundSyncKeepsEveryBandWithoutSwitchingTheOpenOne() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        repository.createBand("Open band", "PLN", "Alice")
        val open = repository.currentBandId!!
        assertTrue(sync.syncNow())
        // Alice joined another band elsewhere (the web, another phone).
        server.bands["elsewhere"] = Band.create("elsewhere", "Joined elsewhere", "PLN", "m-a", "Alice", "alice")

        assertTrue(sync.syncAll())
        assertEquals("Joined elsewhere", repository.local("elsewhere")?.base?.name, "downloaded")
        assertEquals(open, repository.currentBandId, "the open band stays open")
        assertEquals(setOf("Open band", "Joined elsewhere"), repository.allBands().map { it.name }.toSet())
    }

    /** A push the server always refuses is reported, not retried, and can be discarded. */
    @Test
    fun aPushRefusedAsAWholeIsStuckUntilDiscarded() = runTest {
        val server = FakeServer()
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" }, clock = { 7 })
        val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, this)
        repository.load("alice")
        repository.createBand("Stuck Band", "PLN", "Alice")
        val stuck = repository.currentBandId!!
        repository.createBand("Other Band", "PLN", "Alice")
        val other = repository.currentBandId!!
        assertTrue(sync.syncNow())

        server.refusePushes += stuck
        repository.open(stuck)
        repository.dispatch(RenameBand("Never sent"))
        repository.open(other)
        repository.dispatch(RenameBand("Other renamed"))
        assertTrue(sync.syncNow())
        assertEquals(StuckPush(400, "too_many_devices", "Too many devices"), sync.stuck.value[stuck])
        assertEquals("Other renamed", server.bands.getValue(other).name, "other bands still sync")
        assertEquals(setOf(stuck), sync.stuck.value.keys)
        assertEquals(SyncStatus.ERROR, sync.status.value, "not in sync, though the stuck band is not the open one")

        // Not sent again in later rounds, while the band is still pulled.
        repository.open(stuck)
        server.serverSide(stuck, AddMember("bob", "Bob"))
        server.pushedBands.clear()
        assertTrue(sync.syncNow())
        assertEquals(emptyList(), server.pushedBands)
        assertTrue(assertIs<BandState.Ready>(repository.state.value).band.members.any { it.name == "Bob" })
        val exported = repository.pendingAsJson(stuck)
        assertTrue(repository.local(stuck)!!.pending.single().opId in exported && "Never sent" in exported)

        sync.discardStuck(stuck)
        assertEquals(emptyMap(), sync.stuck.value)
        assertEquals(emptyList(), repository.local(stuck)!!.pending)
        assertEquals("Stuck Band", assertIs<BandState.Ready>(repository.state.value).band.name)
        assertTrue(store.values.keys.any { it.startsWith(BandStore.bandKey("alice", stuck) + "-unsent-") }, "a backup is kept")

        server.refusePushes.clear()
        repository.dispatch(RenameBand("Sent at last"))
        assertTrue(sync.syncNow())
        assertEquals("Sent at last", server.bands.getValue(stuck).name)
        assertEquals(emptyList(), repository.local(stuck)!!.pending)
        assertEquals(SyncStatus.SYNCED, sync.status.value)
    }

    /** The discard dialog promises a copy: the files of discarded attachments stay on the device, unqueued. */
    @Test
    fun discardingKeepsTheFilesOfUnsentAttachments() = runTest {
        val server = FakeServer()
        val store = InMemoryStore()
        val repository = BandRepository(store, this, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { "alice:0" }
        val puts = mutableListOf<String>()
        val uploads = AttachmentUploads(store, api, repository) { url, _, _ ->
            puts += url
            200
        }
        val sync = SyncEngine(api, repository, this, uploads = uploads)
        repository.load("alice")
        uploads.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        assertTrue(sync.syncNow())

        server.refusePushes += bandId
        val me = (repository.state.value as BandState.Ready).me!!.id
        assertEquals(null, repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1))))
        assertEquals(null, repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4))))
        uploads.enqueue(PendingUpload(bandId, "a1"), "AAAA")
        uploads.published("a1")
        assertTrue(sync.syncNow())
        assertEquals(setOf(bandId), sync.stuck.value.keys)

        sync.discardStuck(bandId)
        server.refusePushes.clear()
        assertTrue(sync.syncNow())
        assertEquals("AAAA", store.read(AttachmentUploads.fileKey("alice", "a1")), "the file is kept")
        assertEquals(emptyList(), uploads.pending.value, "but no longer waits for an upload")
        assertEquals(emptyList(), puts)
    }

    /** A change made while the backup of the discarded ones is written is not discarded with them. */
    @Test
    fun discardingKeepsAChangeMadeDuringTheBackup() = runTest {
        lateinit var repository: BandRepository
        val store = object : KeyValueStore {
            val values = mutableMapOf<String, String>()
            override suspend fun read(key: String) = values[key]
            override suspend fun write(key: String, value: String) {
                if ("-unsent-" in key) assertEquals(null, repository.dispatch(RenameBand("Made meanwhile")))
                values[key] = value
            }
            override suspend fun remove(key: String) {
                values.remove(key)
            }
        }
        val server = FakeServer()
        repository = BandRepository(store, this, { "id${nextId++}" })
        val sync = SyncEngine(ApiClient(server.client, "https://api.test") { "alice:0" }, repository, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        assertTrue(sync.syncNow())
        server.refusePushes += bandId
        repository.dispatch(RenameBand("Refused"))
        assertTrue(sync.syncNow())

        sync.discardStuck(bandId)
        assertEquals(listOf(RenameBand("Made meanwhile")), repository.local(bandId)!!.pending.map { it.op })
        server.refusePushes.clear()
        assertTrue(sync.syncNow())
        assertEquals("Made meanwhile", server.bands.getValue(bandId).name)
    }
}
