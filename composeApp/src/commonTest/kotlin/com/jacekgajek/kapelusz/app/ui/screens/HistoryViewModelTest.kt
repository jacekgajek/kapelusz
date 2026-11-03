package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.sync.HistoryItem
import com.jacekgajek.kapelusz.app.sync.HistoryPage
import com.jacekgajek.kapelusz.app.sync.ReadOp
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Undo
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The band history screen's paging and reloading ([HistoryViewModel]), and the undo shown at once ([HistoryState]). */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest :
    ExpectSpec({
        coroutineTestScope = true
        isolationMode = IsolationMode.InstancePerRoot
        var nextId = 0
        val pages = mutableMapOf<Pair<String, String?>, HistoryPage>()
        var fails = false
        var synced = 0

        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            return repository
        }

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun item(opId: String, at: Long): HistoryItem {
            val envelope = OpEnvelope(opId, "client", 1, at, CreateEvent("e-$opId", "Gig $opId", LocalDate(2026, 9, 13)))
            return HistoryItem(
                HistoryEntry("user-alice", "alice", at, listOf(envelope), id = "entry-$opId"),
                listOf(ReadOp.Known(envelope)),
            )
        }

        fun screen(repository: BandRepository): HistoryViewModel {
            val state = ready(repository)
            return HistoryViewModel(repository, { synced++ }, state.band.id, state.userId, state.me?.id) { month, next ->
                if (fails) error("offline") else pages.getValue(month to next)
            }
        }

        expect("a month loads its first page and more on demand") {
            val repository = repository(coroutineTestScope)
            val screen = screen(repository)
            val month = screen.month
            pages[month to null] = HistoryPage(month, listOf(item("a", 2000), item("b", 1000)), next = "cursor")
            pages[month to "cursor"] = HistoryPage(month, listOf(item("c", 500)), next = null)
            screen.loadMonth()
            screen.history.shown()?.map { it.opId } shouldBe listOf("a", "b")
            screen.next shouldBe "cursor"
            screen.loadMore()
            screen.loadingMore shouldBe false
            screen.history.shown()?.map { it.opId } shouldBe listOf("a", "b", "c")
            screen.next.shouldBeNull()
        }

        expect("another month starts from nothing and load more fails on its own") {
            val repository = repository(coroutineTestScope)
            val screen = screen(repository)
            val month = screen.month
            pages[month to null] = HistoryPage(month, listOf(item("a", 2000)), next = "cursor")
            screen.loadMonth()
            screen.earlier()
            screen.offset shouldBe 1
            fails = true
            screen.loadMonth()
            withClue("a new month: nothing shown from the old one") { screen.history.lines.shouldBeNull() }
            screen.history.failed shouldBe true
            fails = false
            screen.later()
            screen.loadMonth()
            screen.history.failed shouldBe false
            fails = true
            screen.loadMore()
            withClue("only Load more did not work") { screen.loadMoreFailed shouldBe true }
            withClue("what is shown stays") { screen.history.shown()?.map { it.opId } shouldBe listOf("a") }
        }

        expect("a confirmed undo shows at once then syncs and reloads") {
            val repository = repository(coroutineTestScope)
            val screen = screen(repository)
            val month = screen.month
            pages[month to null] = HistoryPage(month, listOf(item("a", 2000)), next = null)
            screen.loadMonth()
            val history = screen.history
            val undo = Undo("a", CreateEvent("e-a", "Gig a", LocalDate(2026, 9, 13)), BandChange(BandPart(), BandPart()), entry = "entry-a")
            history.ask(undo)
            history.confirming shouldBe undo
            history.dismiss()
            history.confirming.shouldBeNull()
            history.ask(undo)
            val reloads = history.reload
            // The undo went through locally (as the confirmation's Actions.run would do): shown on top of the server's lines.
            repository.dispatch(CreateEvent("e-b", "Gig b", LocalDate(2026, 9, 14))).shouldBeNull()
            history.undone(undo)
            history.confirming.shouldBeNull()
            synced shouldBe 1
            withClue("loaded again behind the line shown at once") { history.reload shouldBe reloads + 1 }
            val shown = history.shown()?.map { it.opId }
            val undoOpId = repository.lastOpId.shouldNotBeNull()
            withClue("this device's undo first, until the server has it") { shown shouldBe listOf(undoOpId, "a") }
        }
    })
