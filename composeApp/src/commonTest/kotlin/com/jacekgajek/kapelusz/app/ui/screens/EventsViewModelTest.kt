package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.view.eventSummaries
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The events tab's search ([EventsViewModel]): opened, prepared, narrowing the list, closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModelTest :
    ExpectSpec({
        coroutineTestScope = true
        val dispatcher = UnconfinedTestDispatcher()
        var nextId = 0

        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(CreateEvent("gig", "Jazz night", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(CreateEvent("wedding", "Wedding in Kraków", LocalDate(2026, 10, 1))).shouldBeNull()
            return repository
        }

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun summaries(repository: BandRepository) = with(ready(repository)) {
            eventSummaries(view, me, showArchived = false, onlyMyEvents = false)
        }

        expect("until something is typed the list is the filtered one") {
            val repository = repository(coroutineTestScope)
            val events = EventsViewModel(repository.state, background = dispatcher)
            val all = summaries(repository)
            events.results(all) shouldBe all
            events.search.shouldBeNull()
            events.openSearch()
            events.searching shouldBe true
            withClue("the keyboard once, when the icon is tapped") { events.focusSearch shouldBe true }
            events.searchFocused()
            events.focusSearch shouldBe false
            withClue("nothing typed yet") { events.results(all) shouldBe all }
            withClue("prepared as soon as the search opens") { events.search.shouldNotBeNull() }
        }

        expect("a query narrows to matching events leaving out ones deleted meanwhile") {
            val repository = repository(coroutineTestScope)
            val events = EventsViewModel(repository.state, background = dispatcher)
            events.openSearch()
            events.query = "kra"
            events.building shouldBe false
            events.results(summaries(repository))?.map { it.event.id } shouldBe listOf("wedding")
            events.query = "nothing like it"
            events.results(summaries(repository)).shouldNotBeNull().shouldBeEmpty()
            events.query = "jazz"
            repository.dispatch(DeleteEvent("gig")).shouldBeNull()
            withClue("found by the old search, but gone") { events.results(summaries(repository)).shouldNotBeNull().shouldBeEmpty() }
        }

        expect("a band change rebuilds the search behind the old results") {
            val repository = repository(coroutineTestScope)
            val slow = StandardTestDispatcher(testScheduler)
            val events = EventsViewModel(repository.state, background = slow)
            events.openSearch()
            events.query = "gig"
            withClue("typed before the search is ready") { events.building shouldBe true }
            testScheduler.advanceUntilIdle()
            events.building shouldBe false
            events.results(summaries(repository))?.map { it.event.id }.shouldNotBeNull().shouldBeEmpty()
            repository.dispatch(CreateEvent("new", "Another gig", LocalDate(2026, 11, 5))).shouldBeNull()
            withClue("the old results while the new search is built") {
                events.results(summaries(repository)).shouldNotBeNull().shouldBeEmpty()
            }
            testScheduler.advanceUntilIdle()
            events.results(summaries(repository))?.map { it.event.id } shouldBe listOf("new")
        }

        expect("closing forgets the query and the search") {
            val repository = repository(coroutineTestScope)
            val events = EventsViewModel(repository.state, background = dispatcher)
            events.openSearch()
            events.query = "jazz"
            events.closeSearch()
            events.searching shouldBe false
            events.query shouldBe ""
            events.search.shouldBeNull()
        }
    })
