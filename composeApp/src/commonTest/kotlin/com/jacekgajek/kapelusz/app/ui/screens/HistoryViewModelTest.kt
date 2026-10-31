package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import com.jacekgajek.kapelusz.app.sync.HistoryItem
import com.jacekgajek.kapelusz.app.sync.HistoryPage
import com.jacekgajek.kapelusz.app.sync.ReadOp
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Undo
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The band history screen's paging and reloading ([HistoryViewModel]), and the undo shown at once ([HistoryState]). */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private var nextId = 0
    private val pages = mutableMapOf<Pair<String, String?>, HistoryPage>()
    private var fails = false
    private var synced = 0

    @BeforeTest
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    private suspend fun TestScope.repository(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        return repository
    }

    private fun ready(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value)

    private fun item(opId: String, at: Long): HistoryItem {
        val envelope = OpEnvelope(opId, "client", 1, at, CreateEvent("e-$opId", "Gig $opId", LocalDate(2026, 9, 13)))
        return HistoryItem(HistoryEntry("user-alice", "alice", at, listOf(envelope), id = "entry-$opId"), listOf(ReadOp.Known(envelope)))
    }

    private fun screen(repository: BandRepository): HistoryViewModel {
        val state = ready(repository)
        return HistoryViewModel(repository, { synced++ }, state.band.id, state.userId, state.me?.id) { month, next ->
            if (fails) error("offline") else pages.getValue(month to next)
        }
    }

    @Test
    fun aMonthLoadsItsFirstPageAndMoreOnDemand() = runTest {
        val repository = repository()
        val screen = screen(repository)
        val month = screen.month
        pages[month to null] = HistoryPage(month, listOf(item("a", 2000), item("b", 1000)), next = "cursor")
        pages[month to "cursor"] = HistoryPage(month, listOf(item("c", 500)), next = null)
        screen.loadMonth()
        assertEquals(listOf("a", "b"), screen.history.shown()?.map { it.opId })
        assertEquals("cursor", screen.next)
        screen.loadMore()
        assertFalse(screen.loadingMore)
        assertEquals(listOf("a", "b", "c"), screen.history.shown()?.map { it.opId })
        assertNull(screen.next)
    }

    @Test
    fun anotherMonthStartsFromNothingAndLoadMoreFailsOnItsOwn() = runTest {
        val repository = repository()
        val screen = screen(repository)
        val month = screen.month
        pages[month to null] = HistoryPage(month, listOf(item("a", 2000)), next = "cursor")
        screen.loadMonth()
        screen.earlier()
        assertEquals(1, screen.offset)
        fails = true
        screen.loadMonth()
        assertNull(screen.history.lines, "a new month: nothing shown from the old one")
        assertTrue(screen.history.failed)
        fails = false
        screen.later()
        screen.loadMonth()
        assertFalse(screen.history.failed)
        fails = true
        screen.loadMore()
        assertTrue(screen.loadMoreFailed, "only Load more did not work")
        assertEquals(listOf("a"), screen.history.shown()?.map { it.opId }, "what is shown stays")
    }

    @Test
    fun aConfirmedUndoShowsAtOnceThenSyncsAndReloads() = runTest {
        val repository = repository()
        val screen = screen(repository)
        val month = screen.month
        pages[month to null] = HistoryPage(month, listOf(item("a", 2000)), next = null)
        screen.loadMonth()
        val history = screen.history
        val undo = Undo("a", CreateEvent("e-a", "Gig a", LocalDate(2026, 9, 13)), BandChange(BandPart(), BandPart()), entry = "entry-a")
        history.ask(undo)
        assertEquals(undo, history.confirming)
        history.dismiss()
        assertNull(history.confirming)
        history.ask(undo)
        val reloads = history.reload
        // The undo went through locally (as the confirmation's Actions.run would do): shown on top of the server's lines.
        assertNull(repository.dispatch(CreateEvent("e-b", "Gig b", LocalDate(2026, 9, 14))))
        history.undone(undo)
        assertNull(history.confirming)
        assertEquals(1, synced)
        assertEquals(reloads + 1, history.reload, "loaded again behind the line shown at once")
        val shown = history.shown()?.map { it.opId }
        assertEquals(listOf(assertNotNull(repository.lastOpId), "a"), shown, "this device's undo first, until the server has it")
    }
}
