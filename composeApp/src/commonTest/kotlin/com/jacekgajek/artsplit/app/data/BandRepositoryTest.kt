package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.RejectionCode
import com.jacekgajek.artsplit.ops.SetRefund
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BandRepositoryTest {
    private var nextId = 0
    private fun ids() = { "id${nextId++}" }

    @Test
    fun startsWithoutBand() = runTest {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.load()
        assertEquals(BandState.NoBand, repository.state.value)
    }

    @Test
    fun operationsArePersistedAsPendingAndReplayedOnLoad() = runTest {
        val store = InMemoryStore()
        val repository = BandRepository(store, this, ids(), clock = { 1000 })
        repository.createBand("The Band", "PLN", "Alice")
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        assertNull(repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))))
        testScheduler.advanceUntilIdle()

        val reloaded = BandRepository(store, TestScope(testScheduler), ids())
        reloaded.load()
        val state = assertIs<BandState.Ready>(reloaded.state.value)
        assertEquals(listOf("Alice", "Bob"), state.band.members.map { it.name })
        assertEquals("Gig", state.band.event("gig")!!.name)
        assertEquals("Alice", state.me!!.name)
        val saved = store.values.getValue(BandRepository.KEY)
        assertEquals(2, ArtSplitJson.decodeFromString(LocalBand.serializer(), saved).pending.size)
    }

    @Test
    fun rejectedOperationsAreNotQueued() = runTest {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.createBand("The Band", "PLN", "Alice")
        val rejection = repository.dispatch(SetRefund("missing", "cost", Money(1)))
        assertEquals(RejectionCode.NOT_FOUND, rejection?.code)
        testScheduler.advanceUntilIdle()
    }
}
