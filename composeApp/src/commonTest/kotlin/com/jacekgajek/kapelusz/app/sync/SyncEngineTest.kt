package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.AttachmentUploadRequest
import com.jacekgajek.kapelusz.api.AttachmentUploadResponse
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.app.data.AttachmentUploads
import com.jacekgajek.kapelusz.app.data.PendingUpload
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import kotlinx.datetime.LocalDate
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A tiny in-memory server running the real shared [Engine], behind Ktor's mock HTTP engine. */
private class FakeServer {
    val bands = mutableMapOf<String, Band>()
    var version = 0
    var unauthorizedOnce = false

    /** The server no longer serves this app version. */
    var updateRequired = false
    var requests = 0
    val appVersionsSeen = mutableListOf<String?>()

    /** Bands their owner deleted: former members get band_deleted. */
    val deletedBands = mutableSetOf<String>()

    /** False simulates a server from before attachments: the route does not exist. */
    var attachmentsRoute = true

    /** Simulates no network; a Throwable that is not an Exception, like the browser's JsException. */
    var offline = false
    val tokensSeen = mutableListOf<String>()

    val client = HttpClient(MockEngine { request ->
        if (offline) throw object : Throwable("Failed to fetch") {}
        requests++
        appVersionsSeen += request.url.parameters["appVersion"]
        if (updateRequired) {
            return@MockEngine respond("""{"error":"update_required","message":"Too old"}""", HttpStatusCode.UpgradeRequired, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val token = request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")
        tokensSeen += token
        if (unauthorizedOnce) {
            unauthorizedOnce = false
            return@MockEngine respond("""{"error":"unauthorized","message":"expired"}""", HttpStatusCode.Unauthorized)
        }
        val user = token.substringBefore(':')
        val body = (request.body as? TextContent)?.text
        val path = request.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val bandInPath = path.split('/').getOrNull(2)
        if (path.startsWith("/bands/") && bandInPath in deletedBands) {
            return@MockEngine respond("""{"error":"band_deleted","message":"Deleted"}""", HttpStatusCode.NotFound, json)
        }
        if (path.startsWith("/bands/") && bandInPath !in bands) {
            return@MockEngine respond("""{"error":"band_not_found","message":"No such band"}""", HttpStatusCode.NotFound, json)
        }
        fun <T> ok(serializer: KSerializer<T>, value: T) = respond(KapeluszJson.encodeToString(serializer, value), HttpStatusCode.OK, json)
        when {
            path == "/me" -> ok(MeResponse.serializer(), MeResponse(user, bands = bands.values.mapNotNull { band ->
                band.memberOfUser(user)?.let { BandSummary(band.id, band.name, it.name, it.role) }
            }))
            path == "/bands" -> {
                val create = KapeluszJson.decodeFromString(CreateBandRequest.serializer(), body!!)
                val band = Band.create(create.bandId, create.name, create.currency, create.ownerMemberId, create.ownerName, user)
                bands[band.id] = band
                ok(BandStateResponse.serializer(), BandStateResponse("v${++version}", band))
            }
            path.endsWith("/state") -> {
                val band = bands.getValue(path.split('/')[2])
                if (request.headers[HttpHeaders.IfNoneMatch] == "v$version") respond("", HttpStatusCode.NotModified)
                else ok(BandStateResponse.serializer(), BandStateResponse("v$version", band))
            }
            path.endsWith("/ops") -> {
                val bandId = path.split('/')[2]
                val push = KapeluszJson.decodeFromString(PushRequest.serializer(), body!!)
                val result = Engine.applyBatch(bands.getValue(bandId), Actor.User(user), push.ops)
                bands[bandId] = result.band
                ok(PushResponse.serializer(), PushResponse("v${++version}", result.band, result.accepted, result.rejected, result.duplicates))
            }
            path.endsWith("/attachments") && !attachmentsRoute -> respond("""{"error":"not_found","message":"No route"}""", HttpStatusCode.NotFound, json)
            path.endsWith("/attachments") -> {
                val upload = KapeluszJson.decodeFromString(AttachmentUploadRequest.serializer(), body!!)
                val listed = bands.getValue(path.split('/')[2]).cost(upload.costId)?.attachments?.any { it.id == upload.attachmentId } == true
                if (!listed) respond("""{"error":"attachment_not_found","message":"No such attachment"}""", HttpStatusCode.NotFound, json)
                else ok(AttachmentUploadResponse.serializer(), AttachmentUploadResponse("https://s3.test/${upload.attachmentId}", mapOf("content-type" to "image/jpeg")))
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    /** Changes made by someone else directly on the server. */
    fun serverSide(bandId: String, vararg ops: Operation) {
        bands[bandId] = ops.fold(bands.getValue(bandId)) { band, op -> (Engine.apply(band, Actor.System, op, "srv", 0) as ApplyResult.Applied).band }
        version++
    }
}

class SyncEngineTest {
    private var tokenCount = 0
    private var nextId = 0

    private fun setup(server: FakeServer, scope: CoroutineScope): Pair<BandRepository, SyncEngine> {
        val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
        val api = ApiClient(server.client, "https://api.test") { force -> if (force) "alice:${++tokenCount}" else "alice:$tokenCount" }
        return repository to SyncEngine(api, repository, scope)
    }

    @Test
    fun createsPushesAndPullsABandMadeOffline() = runTest {
        val server = FakeServer()
        val (repository, sync) = setup(server, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        repository.dispatch(AddMember("bob", "Bob"))
        val bandId = repository.currentBandId!!

        assertTrue(sync.syncNow())
        assertEquals(SyncStatus.SYNCED, sync.status.value)
        assertEquals(listOf("Alice", "Bob"), server.bands.getValue(bandId).members.map { it.name })
        assertEquals(emptyList(), repository.local(bandId)!!.pending)
        assertEquals(false, repository.local(bandId)!!.needsCreate)

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
        assertTrue(store.values.keys.any { it.startsWith(BandRepository.bandKey("alice", lost) + "-unsent-") }, "unsent changes are kept")
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
        val uploads = AttachmentUploads(store, api, repository) { url, _, base64 -> puts += url to base64; 200 }
        val sync = SyncEngine(api, repository, this, uploads = uploads)
        repository.load("alice")
        uploads.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        val bandId = repository.currentBandId!!
        val me = (repository.state.value as BandState.Ready).me!!.id
        assertEquals(null, repository.dispatch(AddCost("c1", me, Money(5000), LocalDate(2026, 9, 1))))
        assertEquals(null, repository.dispatch(AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 4))))
        uploads.enqueue(PendingUpload(bandId, "c1", "a1"), "AAAA")
        // Also queued: a file whose attachment is not on the cost (refused or removed meanwhile): dropped, not uploaded.
        uploads.enqueue(PendingUpload(bandId, "c1", "gone"), "BBBB")

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
        uploads.enqueue(PendingUpload(repository.currentBandId!!, "c1", "a1"), "AAAA")

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
        uploads.enqueue(PendingUpload(repository.currentBandId!!, "c1", "a1"), "AAAA")

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

    @Test
    fun anOutdatedAppStopsSyncingAndSaysSo() = runTest {
        val server = FakeServer()
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        var told = false
        val api = ApiClient(server.client, "https://api.test", appVersion = 11, onUpdateRequired = { told = true }) { "alice:0" }
        val sync = SyncEngine(api, repository, this)
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        assertTrue(sync.syncNow())
        assertTrue(server.appVersionsSeen.all { it == "11" }, "every request carries the app version")

        server.updateRequired = true
        assertFalse(sync.syncNow())
        assertTrue(told)
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
}
