package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.RejectionCode
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.SetRefund
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
        val store = InMemoryStore(mapOf(BandRepository.LEGACY_KEY to ArtSplitJson.encodeToString(LocalBand.serializer(), legacy)))
        val repository = BandRepository(store, this, ids())
        repository.load("google-sub")
        testScheduler.advanceUntilIdle()

        val state = ready(repository)
        assertEquals("Dave", state.me?.name)
        assertEquals(Role.OWNER, state.me?.role)
        assertTrue(repository.local("b1")!!.needsCreate)
        assertNull(store.values[BandRepository.LEGACY_KEY])
        assertTrue(store.values.containsKey(BandRepository.bandKey("b1")))
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
        store.values[BandRepository.bandKey(bandId)] = "{ not valid"

        val reloaded = BandRepository(store, TestScope(testScheduler), ids(), clock = { 42 })
        reloaded.load("alice")
        val state = assertIs<BandState.Unreadable>(reloaded.state.value)
        assertEquals("{ not valid", store.values[state.backupKey])

        reloaded.startOver()
        assertEquals(BandState.NoBand, reloaded.state.value)
        assertNull(store.values[BandRepository.bandKey(bandId)])
        assertEquals("{ not valid", store.values[state.backupKey], "the backup survives starting over")
    }
}
