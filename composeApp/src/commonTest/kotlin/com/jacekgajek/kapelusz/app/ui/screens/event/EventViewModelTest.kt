package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.EventTab
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import com.jacekgajek.kapelusz.app.data.SharedAttached
import com.jacekgajek.kapelusz.app.data.SharedHandover
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The event screen's state ([EventViewModel]): what each role sees, and what happens to files shared to the event. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventViewModelTest {
    private var nextId = 0

    @BeforeTest
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    /** Alice (owner, the user) and Bob; a gig Alice plays alone and a show they both play. */
    private suspend fun TestScope.repository(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        val alice = band(repository).members.single().id
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        assertNull(repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))))
        assertNull(repository.dispatch(AddParticipant("gig", alice)))
        assertNull(repository.dispatch(CreateEvent("show", "Show", LocalDate(2026, 10, 1))))
        assertNull(repository.dispatch(AddParticipant("show", alice)))
        assertNull(repository.dispatch(AddParticipant("show", "bob")))
        return repository
    }

    private fun band(repository: BandRepository): Band = assertIs<BandState.Ready>(repository.state.value).band

    /** The band as Bob sees it once he has joined as an artist (linking happens by accepting an invite, on the server). */
    private fun asBob(repository: BandRepository): MutableStateFlow<BandState> {
        val band = band(repository)
        val joined = band.copy(members = band.members.map { if (it.id == "bob") it.copy(userId = "user-bob", role = Role.ARTIST) else it })
        return MutableStateFlow(BandState.Ready(joined, "user-bob"))
    }

    @Test
    fun aLeaderSeesEveryEventOnThreePages() = runTest {
        val repository = repository()
        val screen = EventViewModel("gig", repository.state)
        assertFalse(screen.artist)
        assertEquals("Gig", screen.event?.name)
        assertNotNull(screen.settlement)
        assertEquals(EventTab.entries, screen.tabs)
    }

    @Test
    fun anArtistSeesOnlyTheEventsTheyPlayOnTwoPages() = runTest {
        val repository = repository()
        val asBob = asBob(repository)
        val show = EventViewModel("show", asBob)
        assertTrue(show.artist)
        assertEquals("Show", show.event?.name)
        assertEquals(listOf(EventTab.GIG, EventTab.MONEY), show.tabs)
        val gig = EventViewModel("gig", asBob)
        assertNull(gig.event, "not playing it: as if it did not exist")
        assertNull(gig.settlement)
    }

    @Test
    fun theEventFollowsTheBandAndIsGoneOnceDeleted() = runTest {
        val repository = repository()
        val screen = EventViewModel("gig", repository.state)
        assertNull(repository.dispatch(DeleteEvent("gig")))
        assertNull(screen.event)
    }

    @Test
    fun sharedFilesOpenTheCostFormOrAreDroppedWhenTheEventIsGone() = runTest {
        val repository = repository()
        val screen = EventViewModel("gig", repository.state)
        assertNull(screen.handoverAction(null))
        assertNull(screen.handoverAction(SharedHandover("show", toCost = true, emptyList())), "another event's")
        assertNull(screen.handoverAction(SharedHandover("gig", toCost = false, emptyList())), "to the event itself: the section takes them")
        assertEquals(HandoverAction.ADD_COST, screen.handoverAction(SharedHandover("gig", toCost = true, emptyList())))
        assertNull(repository.dispatch(DeleteEvent("gig")))
        assertEquals(HandoverAction.DROP, screen.handoverAction(SharedHandover("gig", toCost = true, emptyList())))
    }

    @Test
    fun attachedFilesAreTakenOverOnceForThisEventOfThisBand() = runTest {
        val repository = repository()
        val bandId = band(repository).id
        val screen = EventViewModel("gig", repository.state)
        val owner = AttachmentOwner.OfEvent("gig")
        assertFalse(screen.takeAttached(null, bandId))
        assertFalse(screen.takeAttached(SharedAttached(bandId, "show", owner, listOf("a1"), failed = 0), bandId))
        assertFalse(screen.takeAttached(SharedAttached("other-band", "gig", owner, listOf("a1"), failed = 0), bandId))
        assertNull(screen.attached)
        val mine = SharedAttached(bandId, "gig", owner, listOf("a1"), failed = 0)
        assertTrue(screen.takeAttached(mine, bandId))
        assertEquals(mine, screen.attached)
        screen.attachedShown()
        assertNull(screen.attached)
    }
}
