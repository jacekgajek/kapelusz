package com.jacekgajek.artsplit.app.sync

import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.BandSummary
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.app.data.BandRepository
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.data.InMemoryStore
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.ApplyResult
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A tiny in-memory server running the real shared [Engine], behind Ktor's mock HTTP engine. */
private class FakeServer {
    val bands = mutableMapOf<String, Band>()
    var version = 0
    var unauthorizedOnce = false

    /** Simulates no network; a Throwable that is not an Exception, like the browser's JsException. */
    var offline = false
    val tokensSeen = mutableListOf<String>()

    val client = HttpClient(MockEngine { request ->
        if (offline) throw object : Throwable("Failed to fetch") {}
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
        if (path.startsWith("/bands/") && bandInPath !in bands) {
            return@MockEngine respond("""{"error":"not_found","message":"No such band"}""", HttpStatusCode.NotFound, json)
        }
        fun <T> ok(serializer: KSerializer<T>, value: T) = respond(ArtSplitJson.encodeToString(serializer, value), HttpStatusCode.OK, json)
        when {
            path == "/me" -> ok(MeResponse.serializer(), MeResponse(user, bands = bands.values.mapNotNull { band ->
                band.memberOfUser(user)?.let { BandSummary(band.id, band.name, it.name, it.role) }
            }))
            path == "/bands" -> {
                val create = ArtSplitJson.decodeFromString(CreateBandRequest.serializer(), body!!)
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
                val push = ArtSplitJson.decodeFromString(PushRequest.serializer(), body!!)
                val result = Engine.applyBatch(bands.getValue(bandId), Actor.User(user), push.ops)
                bands[bandId] = result.band
                ok(PushResponse.serializer(), PushResponse("v${++version}", result.band, result.accepted, result.rejected, result.duplicates))
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
        val refused = backgroundScope.async { sync.rejections.first() }
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

        val message = backgroundScope.async { sync.lostAccess.first() }
        assertTrue(sync.syncNow())
        assertEquals("Lost Band", message.await())
        assertEquals("Kept and renamed", server.bands.getValue(kept).name)
        assertEquals(listOf("Kept and renamed"), repository.index.value!!.bands.map { it.name })
        assertTrue(store.values.keys.any { it.startsWith(BandRepository.bandKey(lost) + "-unsent-") }, "unsent changes are kept")
    }
}
