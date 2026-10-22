package com.jacekgajek.splitart.app.data

import com.jacekgajek.splitart.api.BandSummary
import com.jacekgajek.splitart.api.PushResponse
import com.jacekgajek.splitart.model.SplitArtJson
import com.jacekgajek.splitart.model.Band
import com.jacekgajek.splitart.model.Money
import com.jacekgajek.splitart.model.Role
import com.jacekgajek.splitart.ops.AddMember
import com.jacekgajek.splitart.ops.CreateEvent
import com.jacekgajek.splitart.ops.RejectionCode
import com.jacekgajek.splitart.ops.RenameBand
import com.jacekgajek.splitart.ops.SetRefund
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun adoptsTheBandCreatedBeforeSignIn() = runTest {
        val legacyBand = Band.create("b1", "Old Band", "PLN", "m1", "Dave", BandRepository.LOCAL_USER_ID)
        val legacy = LocalBand(base = legacyBand, clientId = "c", userId = BandRepository.LOCAL_USER_ID)
        val store = InMemoryStore(mapOf(BandRepository.LEGACY_KEY to SplitArtJson.encodeToString(LocalBand.serializer(), legacy)))
        val repository = BandRepository(store, this, ids())
        repository.load("google-sub")
        testScheduler.advanceUntilIdle()

        val state = ready(repository)
        assertEquals("Dave", state.me?.name)
        assertEquals(Role.OWNER, state.me?.role)
        assertTrue(repository.local("b1")!!.needsCreate)
        assertNull(store.values[BandRepository.LEGACY_KEY])
        assertTrue(store.values.containsKey(BandRepository.bandKey("google-sub", "b1")))
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
    fun movesUnscopedKeysOfTheSameUser() = runTest {
        val band = Band.create("b1", "M3 Band", "PLN", "m1", "Alice", "alice")
        val local = LocalBand(base = band, version = "v1", clientId = "c", userId = "alice")
        val index = BandIndex("alice", "b1", listOf(BandSummary("b1", "M3 Band", "Alice", Role.OWNER)))
        val store = InMemoryStore(
            mapOf(
                BandRepository.UNSCOPED_INDEX_KEY to SplitArtJson.encodeToString(BandIndex.serializer(), index),
                "band-b1" to SplitArtJson.encodeToString(LocalBand.serializer(), local),
            ),
        )
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        assertEquals("M3 Band", ready(repository).band.name)
        assertNull(store.values["band-b1"])
        assertNull(store.values[BandRepository.UNSCOPED_INDEX_KEY])
        assertTrue(store.values.containsKey(BandRepository.bandKey("alice", "b1")))
    }

    @Test
    fun flushWritesPendingChangesImmediately() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        repository.createBand("The Band", "PLN", "Alice")
        repository.dispatch(AddMember("bob", "Bob"))
        repository.flush()
        val saved = SplitArtJson.decodeFromString(LocalBand.serializer(), store.values.getValue(BandRepository.bandKey("alice", repository.currentBandId!!)))
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
        store.write(BandRepository.bandKey("alice", "b1"), SplitArtJson.encodeToString(LocalBand.serializer(), old))
        store.write(BandRepository.indexKey("alice"), SplitArtJson.encodeToString(BandIndex.serializer(), BandIndex("alice", "b1", listOf(BandSummary("b1", "Night Owls", "Alice", Role.OWNER)))))
        val repository = BandRepository(store, this, ids())
        repository.load("alice")
        assertNull(repository.local("b1")!!.version)
        repository.flush()
        val saved = SplitArtJson.decodeFromString(LocalBand.serializer(), store.read(BandRepository.bandKey("alice", "b1"))!!)
        assertEquals(BandRepository.CURRENT_SCHEMA, saved.schema)
    }
}
