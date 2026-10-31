package com.jacekgajek.kapelusz.app.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.ui.CostRoute
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UpdateCost
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

/** The cost form's rules, without a screen: what it offers, what Save sends, what survives a rotation. */
@OptIn(ExperimentalCoroutinesApi::class)
class CostViewModelTest {
    private val today = LocalDate(2026, 10, 31)
    private var nextId = 0

    @BeforeTest
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    /** Alice (owner, the user) and Bob; a gig Alice and Bob play, and an archived-free second event for Alice only. */
    private suspend fun TestScope.band(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        assertNull(repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))))
        assertNull(repository.dispatch(AddParticipant("gig", me(repository))))
        assertNull(repository.dispatch(AddParticipant("gig", "bob")))
        assertNull(repository.dispatch(CreateEvent("solo", "Solo", LocalDate(2026, 10, 1))))
        assertNull(repository.dispatch(AddParticipant("solo", me(repository))))
        return repository
    }

    private fun me(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value).me!!.id

    private fun ready(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value)

    private class Recorder(private val repository: BandRepository) {
        val ops = mutableListOf<Operation>()
        fun dispatch(op: Operation) = repository.dispatch(op).also { ops += op }
    }

    private fun form(
        repository: BandRepository,
        route: CostRoute,
        recorder: Recorder = Recorder(repository),
        handle: SavedStateHandle = SavedStateHandle(),
    ) = CostViewModel(route, repository.state, recorder::dispatch, { _, _ -> }, emptyList(), handle, today) { it.toString() }

    @Test
    fun aNewCostIsTheUsersOwnTodayAndSavesOnceTheAmountIsTyped() = runTest {
        val repository = band()
        val form = form(repository, CostRoute())
        assertEquals(me(repository), form.draft.payerId)
        assertEquals(today, form.draft.date)
        assertFalse(form.state.canSave, "no amount yet")
        assertEquals(listOf(null, "solo", "gig"), form.state.events, "her events, newest first")
        form.amount("12.50")
        form.category(CostCategory.TRAVEL)
        form.event("gig")
        assertTrue(form.state.canSave)
        val outcome = assertIs<CostOutcome.Added>(form.save())
        assertFalse(outcome.forOther)
        val cost = ready(repository).band.cost(outcome.costId)!!
        assertEquals(Money.parse("12.50"), cost.amount)
        assertEquals("gig", cost.eventId)
        assertEquals(CostCategory.TRAVEL, cost.category)
        assertNull(form.save(), "once only: a second tap must not add it again")
    }

    @Test
    fun aLeadMayPayForSomeoneElseAndIsToldSo() = runTest {
        val repository = band()
        val form = form(repository, CostRoute())
        assertEquals(listOf(me(repository), "bob"), form.state.payers.map { it.id })
        form.payer("bob")
        form.amount("5")
        assertTrue(assertIs<CostOutcome.Added>(form.save()).forOther)
    }

    @Test
    fun anotherPayerDropsAnEventTheyAreNotIn() = runTest {
        val repository = band()
        val form = form(repository, CostRoute())
        form.event("solo")
        form.payer("bob")
        assertNull(form.draft.eventId, "Bob does not play Solo")
        assertEquals(listOf(null, "gig"), form.state.events)
    }

    @Test
    fun openedFromAnEventTheEventIsFixedAndOnlyItsPlayersPaid() = runTest {
        val repository = band()
        val form = form(repository, CostRoute(forEventId = "solo"))
        assertEquals("solo", form.state.forEvent?.id)
        assertEquals("solo", form.draft.eventId)
        assertEquals(emptyList(), form.state.events, "nothing to choose")
        assertEquals(listOf(me(repository)), form.state.payers.map { it.id })
    }

    @Test
    fun editingSendsOnlyWhatChangedSinceTheFormOpened() = runTest {
        val repository = band()
        assertNull(repository.dispatch(AddCost("c1", me(repository), Money.parse("20"), today, "gig", CostCategory.FOOD, "Pizza")))
        val recorder = Recorder(repository)
        val form = form(repository, CostRoute("c1"), recorder)
        assertEquals("20.00", form.draft.amountText)
        assertFalse(form.state.isNew)
        form.description("Pizza and drinks")
        assertIs<CostOutcome.Updated>(form.save())
        val sent = assertIs<UpdateCost>(recorder.ops.single())
        assertEquals("Pizza and drinks", sent.description?.value)
        assertNull(sent.amount, "untouched fields are not sent, so another device's change to them survives")
        assertNull(sent.date)
        assertNull(sent.category)
    }

    @Test
    fun aChangeMadeElsewhereWhileTheFormIsOpenIsNotOverwritten() = runTest {
        val repository = band()
        assertNull(repository.dispatch(AddCost("c1", me(repository), Money.parse("20"), today, description = "Pizza")))
        val recorder = Recorder(repository)
        val form = form(repository, CostRoute("c1"), recorder)
        // Another device corrected the amount meanwhile (synced in while the form was open).
        assertNull(repository.dispatch(UpdateCost("c1", amount = Change(Money.parse("30")))))
        form.description("Pizza and drinks")
        assertIs<CostOutcome.Updated>(form.save())
        val sent = assertIs<UpdateCost>(recorder.ops.single())
        assertNull(sent.amount, "the untouched amount is not sent, so the correction survives")
        assertEquals(Money.parse("30"), ready(repository).band.cost("c1")!!.amount)
        assertEquals("Pizza and drinks", ready(repository).band.cost("c1")!!.description)
    }

    @Test
    fun deletingAsksFirstThenGoesBack() = runTest {
        val repository = band()
        assertNull(repository.dispatch(AddCost("c1", me(repository), Money.parse("20"), today)))
        val form = form(repository, CostRoute("c1"))
        assertTrue(form.state.canDelete)
        form.askDelete()
        assertEquals(CostDialog.CONFIRM_DELETE, form.dialog)
        assertIs<CostOutcome.Deleted>(form.delete())
        assertNull(form.dialog)
        assertNull(ready(repository).band.cost("c1"))
    }

    @Test
    fun aCostThatIsGoneIsNotFound() = runTest {
        val form = form(band(), CostRoute("nope"))
        assertTrue(form.state.notFound)
        form.amount("5")
        assertFalse(form.state.canSave)
    }
}
