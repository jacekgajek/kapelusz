package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.RejectionCode
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetRefund
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class BandRepositoryTest {
    private var nextId = 0
    private fun ids() = { "id${nextId++}" }
    private fun ready(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value)

    @Test
    fun startsWithoutBand() = runTest {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.load("alice")
        assertEquals(BandState.NoBand, repository.state.value)
    }

    @Test
    fun createdBandIsQueuedForCreationAndPersistedWithPendingOps() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids(), clock = { 1000 })
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        assertNull(repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))))
        testScheduler.advanceUntilIdle()

        val bandId = ready(repository).band.id
        assertEquals(listOf(bandId), repository.bandsToPush())
        val reloaded = BandRepository(store, TestScope(testScheduler), ids())
        reloaded.load("alice")
        val state = ready(reloaded)
        assertEquals(listOf("Alice", "Bob"), state.band.members.map { it.name })
        assertEquals("Alice", state.me!!.name)
        assertTrue(reloaded.local(bandId)!!.needsCreate)
        assertEquals(2, reloaded.local(bandId)!!.pending.size)
        assertEquals(listOf("The Band"), reloaded.index.value!!.bands.map { it.name })
    }

    /** V5-M1 A1: a change made while the band is being written is written too, not taken as saved with the old copy. */
    @Test
    fun aChangeMadeDuringAWriteIsWrittenToo() = runTest {
        val inner = InMemoryStore()
        val gate = CompletableDeferred<Unit>()
        var holdNextBandWrite = false
        val store = object : KeyValueStore by inner {
            override suspend fun write(key: String, value: String) {
                if (holdNextBandWrite && key.startsWith("band-alice-")) {
                    holdNextBandWrite = false
                    gate.await()
                }
                inner.write(key, value)
            }
        }
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        testScheduler.advanceUntilIdle()
        val bandId = ready(repository).band.id

        holdNextBandWrite = true
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        testScheduler.runCurrent()
        // The write of the band with Bob is under way; Carol comes now.
        assertNull(repository.dispatch(AddMember("carol", "Carol")))
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val reloaded = BandRepository(inner, TestScope(testScheduler), ids())
        reloaded.load("alice")
        assertEquals(bandId, ready(reloaded).band.id)
        assertEquals(listOf("Alice", "Bob", "Carol"), ready(reloaded).band.members.map { it.name })
    }

    @Test
    fun rejectedOperationsAreNotQueued() = runTest {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        val before = ready(repository).band
        assertEquals(RejectionCode.NOT_FOUND, repository.dispatch(SetRefund("missing", "cost", Money(1)))?.code)
        assertEquals(before, ready(repository).band)
        assertEquals(emptyList(), repository.local(before.id)!!.pending)
    }

    @Test
    fun serverStateIsTheNewBaseAndUnsentChangesAreReappliedOnTop() = runTest {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        val bandId = ready(repository).band.id
        val created = repository.local(bandId)!!.base
        repository.onCreated(bandId, created, "v1")
        repository.dispatch(RenameBand("Sent"))
        val sent = repository.local(bandId)!!.pending
        repository.dispatch(AddMember("bob", "Bob")) // made while the push is in flight

        val serverBand = created.copy(name = "Sent", members = created.members + created.members.first().copy(id = "carol", name = "Carol", userId = null, role = Role.MEMBER))
        repository.onPushed(bandId, PushResponse("v2", serverBand, accepted = sent.map { it.opId }, rejected = emptyMap(), duplicates = emptyList()))

        val local = repository.local(bandId)!!
        assertEquals("v2", local.version)
        assertEquals(1, local.pending.size)
        assertEquals(listOf("Alice", "Carol", "Bob"), ready(repository).band.members.map { it.name })
    }

    @Test
    fun switchesBetweenBands() = runTest {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.load("alice")
        repository.createBand("First", "PLN", "Alice")
        val first = ready(repository).band.id
        repository.createBand("Second", "EUR", "Alice")
        assertEquals("Second", ready(repository).band.name)
        repository.open(first)
        assertEquals("First", ready(repository).band.name)
        assertEquals(first, repository.currentBandId)
    }

    @Test
    fun unreadableDataIsBackedUpBeforeStartingOver() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids(), clock = { 42 })
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        val bandId = ready(repository).band.id
        testScheduler.advanceUntilIdle()
        store.values[BandRepository.bandKey("alice", bandId)] = "{ not valid"

        val reloaded = BandRepository(store, TestScope(testScheduler), ids(), clock = { 42 })
        reloaded.load("alice")
        val state = assertIs<BandState.Unreadable>(reloaded.state.value)
        assertEquals("{ not valid", store.values[state.backupKey])

        reloaded.startOver()
        assertEquals(BandState.NoBand, reloaded.state.value)
        assertNull(store.values[BandRepository.bandKey("alice", bandId)])
        assertEquals("{ not valid", store.values[state.backupKey], "the backup survives starting over")
    }

    @Test
    fun bandsThatAreNotOpenStillSyncAfterARestart() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        repository.createBand("Made offline", "PLN", "Alice")
        val offline = repository.currentBandId!!
        repository.createBand("Open one", "PLN", "Alice")
        testScheduler.advanceUntilIdle()

        val restarted = BandRepository(store, TestScope(testScheduler), ids())
        restarted.load("alice")
        assertEquals("Open one", ready(restarted).band.name)
        assertTrue(offline in restarted.bandsToPush(), "the band made offline is still queued for upload")
        restarted.onBandList(emptyList()) // the server does not know it yet
        assertTrue(restarted.index.value!!.bands.any { it.id == offline }, "and it stays in the band list")
    }

    @Test
    fun anotherAccountOnTheSameDeviceDoesNotSeeTheBands() = runTest {
        val store = InMemoryStore()
        val alice = BandRepository(store, this, ids())
        alice.load("alice")
        alice.createBand("Alice's band", "PLN", "Alice")
        alice.dispatch(AddMember("bob", "Bob"))
        val bandId = alice.currentBandId!!
        testScheduler.advanceUntilIdle()

        val bob = BandRepository(store, TestScope(testScheduler), ids())
        bob.load("bob")
        assertEquals(BandState.NoBand, bob.state.value)
        assertEquals(emptyList(), bob.bandsToPush())
        assertEquals(false, bob.hasStored(bandId))

        val aliceAgain = BandRepository(store, TestScope(testScheduler), ids())
        aliceAgain.load("alice")
        assertEquals(1, aliceAgain.local(bandId)!!.pending.size, "Alice's unsent change is still there")
    }

    @Test
    fun flushWritesPendingChangesImmediately() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        repository.dispatch(AddMember("bob", "Bob"))
        repository.flush()
        val saved = KapeluszJson.decodeFromString(LocalBand.serializer(), store.values.getValue(BandRepository.bandKey("alice", repository.currentBandId!!)))
        assertEquals(2L, saved.nextSeq)
    }

    @Test
    fun forgettingADeletedBandOpensAnotherOne() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        repository.createBand("Kept", "PLN", "Alice")
        val kept = repository.currentBandId!!
        repository.createBand("Deleted", "PLN", "Alice")
        val deleted = repository.currentBandId!!
        repository.flush()

        repository.forgetDeleted(deleted)
        assertEquals(kept, repository.currentBandId)
        assertEquals(listOf("Kept"), repository.index.value!!.bands.map { it.name })
        assertEquals(null, store.read(BandRepository.bandKey("alice", deleted)))
        assertTrue(store.values.keys.none { it.contains(deleted) }, "no backup is kept for a band deleted on purpose")
    }

    @Test
    fun aBandStoredByAnOlderAppIsDownloadedInFullOnce() = runTest {
        // An older app dropped fields it did not know but kept the server's version, so "not modified" would never
        // bring them back.
        val store = InMemoryStore()
        val band = Band.create("b1", "Night Owls", "PLN", "m1", "Alice", "alice")
        val old = LocalBand(base = band, version = "\"etag-1\"", clientId = "c", userId = "alice")
        store.write(BandRepository.bandKey("alice", "b1"), KapeluszJson.encodeToString(LocalBand.serializer(), old))
        store.write(BandRepository.indexKey("alice"), KapeluszJson.encodeToString(BandIndex.serializer(), BandIndex("alice", "b1", listOf(BandSummary("b1", "Night Owls", "Alice", Role.OWNER)))))
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        assertNull(repository.local("b1")!!.version)
        repository.flush()
        val saved = KapeluszJson.decodeFromString(LocalBand.serializer(), store.read(BandRepository.bandKey("alice", "b1"))!!)
        assertEquals(BandRepository.CURRENT_SCHEMA, saved.schema)
    }

    /** ITERATION_8 §2: an unsent change of a kind this version doesn't know (a MoveMember) is dropped, the rest kept. */
    @Test
    fun anUnsentChangeOfAnUnknownKindIsDropped() = runTest {
        val store = InMemoryStore()
        val band = Band.create("b1", "Night Owls", "PLN", "m1", "Alice", "alice")
        val stored = LocalBand(base = band, version = "\"etag-1\"", clientId = "c", userId = "alice", schema = BandRepository.CURRENT_SCHEMA)
        val pending = """[{"opId":"o1","clientId":"c","seq":1,"timestamp":1,"op":{"type":"MoveMember","memberId":"m1","toIndex":0}},""" +
            """{"opId":"o2","clientId":"c","seq":2,"timestamp":2,"op":{"type":"RenameBand","name":"Owls"}}]"""
        val raw = KapeluszJson.encodeToString(LocalBand.serializer(), stored).replaceFirst("{", "{\"pending\":$pending,")
        store.write(BandRepository.bandKey("alice", "b1"), raw)
        store.write(BandRepository.indexKey("alice"), KapeluszJson.encodeToString(BandIndex.serializer(), BandIndex("alice", "b1", listOf(BandSummary("b1", "Night Owls", "Alice", Role.OWNER)))))
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        assertEquals(listOf("o2"), repository.local("b1")!!.pending.map { it.opId })
        assertEquals("Owls", ready(repository).band.name)
    }

    @Test
    fun aBandAlreadyOnTheDeviceIsNotOverwrittenByABackgroundDownload() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        testScheduler.advanceUntilIdle()
        val bandId = ready(repository).band.id
        // The index is lost (unreadable): the band's file is still there, with its unsent change.
        store.values["band-index-alice"] = "{ not json"
        val reloaded = BandRepository(store, TestScope(testScheduler), ids())
        reloaded.load("alice")
        assertTrue(store.values.keys.any { it.startsWith("band-index-alice-backup-") }, "the unreadable index is kept")
        reloaded.storeFromServer(com.jacekgajek.kapelusz.model.Band.create(bandId, "The Band", "PLN", "alice", "Alice", "alice"), "v1")
        assertEquals(1, reloaded.local(bandId)!!.pending.size, "the unsent change survives the background download")
    }

    @Test
    fun aFailedWriteIsKeptAndTriedAgain() = runTest {
        val failing = object : KeyValueStore {
            val values = mutableMapOf<String, String>()
            var full = false
            override suspend fun read(key: String) = values[key]
            override suspend fun write(key: String, value: String) {
                if (full) error("No space left on device")
                values[key] = value
            }
            override suspend fun remove(key: String) {
                values.remove(key)
            }
        }
        val repository = BandRepository(failing, this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        testScheduler.advanceUntilIdle()
        val bandId = ready(repository).band.id
        failing.full = true
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        testScheduler.runCurrent() // the write fails, without crashing
        failing.full = false
        testScheduler.advanceTimeBy(BandRepository.FLUSH_RETRY_MS + 1)
        testScheduler.runCurrent()
        val stored = failing.values.getValue("band-alice-$bandId")
        assertTrue("Bob" in stored, "written once the device has space again")
    }
}
