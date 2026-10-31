package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.view.eventSummaries
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The events tab's search ([EventsViewModel], ITERATION_6 §6): opened, prepared, narrowing the list, closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var nextId = 0

    @BeforeTest
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    private suspend fun TestScope.repository(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        assertNull(repository.dispatch(CreateEvent("gig", "Jazz night", LocalDate(2026, 9, 13))))
        assertNull(repository.dispatch(CreateEvent("wedding", "Wedding in Kraków", LocalDate(2026, 10, 1))))
        return repository
    }

    private fun ready(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value)

    private fun summaries(repository: BandRepository) = with(ready(repository)) {
        eventSummaries(view, me, showArchived = false, onlyMyEvents = false)
    }

    @Test
    fun untilSomethingIsTypedTheListIsTheFilteredOne() = runTest {
        val repository = repository()
        val events = EventsViewModel(repository.state, background = dispatcher)
        val all = summaries(repository)
        assertEquals(all, events.results(all))
        assertNull(events.search)
        events.openSearch()
        assertTrue(events.searching)
        assertTrue(events.focusSearch, "the keyboard once, when the icon is tapped")
        events.searchFocused()
        assertFalse(events.focusSearch)
        assertEquals(all, events.results(all), "nothing typed yet")
        assertNotNull(events.search, "prepared as soon as the search opens")
    }

    @Test
    fun aQueryNarrowsToMatchingEventsLeavingOutOnesDeletedMeanwhile() = runTest {
        val repository = repository()
        val events = EventsViewModel(repository.state, background = dispatcher)
        events.openSearch()
        events.query = "kra"
        assertFalse(events.building)
        assertEquals(listOf("wedding"), events.results(summaries(repository))?.map { it.event.id })
        events.query = "nothing like it"
        assertEquals(emptyList(), events.results(summaries(repository)))
        events.query = "jazz"
        assertNull(repository.dispatch(DeleteEvent("gig")))
        assertEquals(emptyList(), events.results(summaries(repository)), "found by the old search, but gone")
    }

    @Test
    fun aBandChangeRebuildsTheSearchBehindTheOldResults() = runTest {
        val repository = repository()
        val slow = StandardTestDispatcher(testScheduler)
        val events = EventsViewModel(repository.state, background = slow)
        events.openSearch()
        events.query = "gig"
        assertTrue(events.building, "typed before the search is ready")
        testScheduler.advanceUntilIdle()
        assertFalse(events.building)
        assertEquals(emptyList(), events.results(summaries(repository))?.map { it.event.id })
        assertNull(repository.dispatch(CreateEvent("new", "Another gig", LocalDate(2026, 11, 5))))
        assertEquals(emptyList(), events.results(summaries(repository)), "the old results while the new search is built")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("new"), events.results(summaries(repository))?.map { it.event.id })
    }

    @Test
    fun closingForgetsTheQueryAndTheSearch() = runTest {
        val repository = repository()
        val events = EventsViewModel(repository.state, background = dispatcher)
        events.openSearch()
        events.query = "jazz"
        events.closeSearch()
        assertFalse(events.searching)
        assertEquals("", events.query)
        assertNull(events.search)
    }
}
