package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.intents.Carry
import com.jacekgajek.kapelusz.app.settings.EventTab
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The event screen's state ([EventViewModel]): what each role sees, and what happens to files shared to the event. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventViewModelTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        fun band(repository: BandRepository): Band = repository.state.value.shouldBeInstanceOf<BandState.Ready>().band

        /** Alice (owner, the user) and Bob; a gig Alice plays alone and a show they both play. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            val alice = band(repository).members.single().id
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", alice)).shouldBeNull()
            repository.dispatch(CreateEvent("show", "Show", LocalDate(2026, 10, 1))).shouldBeNull()
            repository.dispatch(AddParticipant("show", alice)).shouldBeNull()
            repository.dispatch(AddParticipant("show", "bob")).shouldBeNull()
            return repository
        }

        /** The band as Bob sees it once he has joined as an artist (linking happens by accepting an invite, on the server). */
        fun asBob(repository: BandRepository): MutableStateFlow<BandState> {
            val band = band(repository)
            val joined = band.copy(
                members = band.members.map { if (it.id == "bob") it.copy(userId = "user-bob", role = Role.ARTIST) else it },
            )
            return MutableStateFlow(BandState.Ready(joined, "user-bob"))
        }

        expect("a leader sees every event on three pages") {
            val repository = repository(coroutineTestScope)
            val screen = EventViewModel("gig", repository.state)
            screen.artist shouldBe false
            screen.event?.name shouldBe "Gig"
            screen.settlement.shouldNotBeNull()
            screen.tabs shouldBe EventTab.entries
        }

        expect("an artist sees only the events they play on two pages") {
            val repository = repository(coroutineTestScope)
            val asBob = asBob(repository)
            val show = EventViewModel("show", asBob)
            show.artist shouldBe true
            show.event?.name shouldBe "Show"
            show.tabs shouldBe listOf(EventTab.GIG, EventTab.MONEY)
            val gig = EventViewModel("gig", asBob)
            withClue("not playing it: as if it did not exist") { gig.event.shouldBeNull() }
            gig.settlement.shouldBeNull()
        }

        expect("the event follows the band and is gone once deleted") {
            val repository = repository(coroutineTestScope)
            val screen = EventViewModel("gig", repository.state)
            repository.dispatch(DeleteEvent("gig")).shouldBeNull()
            screen.event.shouldBeNull()
        }

        expect("what the screen opened with is taken once") {
            val repository = repository(coroutineTestScope)
            EventViewModel("gig", repository.state).attached.shouldBeNull()
            val attached = Carry.Attached(AttachmentOwner.OfEvent("gig"), listOf("a1"), failed = 0)
            val screen = EventViewModel("gig", repository.state, carry = attached)
            screen.attached shouldBe attached
            withClue("not what the carry wasn't") { screen.takeCollectedFiles().shouldBeNull() }
            screen.attachedShown()
            screen.attached.shouldBeNull()
            val collected = EventViewModel("gig", repository.state, carry = Carry.NewCollected(emptyList()))
            collected.takeCollectedFiles().shouldNotBeNull().shouldBeEmpty()
            withClue("once: the dialog opened with them") { collected.takeCollectedFiles().shouldBeNull() }
        }

        expect("the details editor opens with the event and closes") {
            val repository = repository(coroutineTestScope)
            val screen = EventViewModel("gig", repository.state)
            screen.details.shouldBeNull()

            screen.editDetails(band(repository).event("gig")!!, band(repository))
            screen.details.shouldNotBeNull().opened.id shouldBe "gig"

            screen.dismissDetails()
            screen.details.shouldBeNull()
        }
    })
