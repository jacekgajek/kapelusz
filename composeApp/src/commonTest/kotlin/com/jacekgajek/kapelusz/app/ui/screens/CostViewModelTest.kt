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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The cost form's rules, without a screen: what it offers, what Save sends, what survives a rotation. */
@OptIn(ExperimentalCoroutinesApi::class)
class CostViewModelTest :
    ExpectSpec({
        coroutineTestScope = true
        val today = LocalDate(2026, 10, 31)
        var nextId = 0

        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        /** Alice (owner, the user) and Bob; a gig Alice and Bob play, and an archived-free second event for Alice only. */
        suspend fun band(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", me(repository))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "bob")).shouldBeNull()
            repository.dispatch(CreateEvent("solo", "Solo", LocalDate(2026, 10, 1))).shouldBeNull()
            repository.dispatch(AddParticipant("solo", me(repository))).shouldBeNull()
            return repository
        }

        class Recorder(private val repository: BandRepository) {
            val ops = mutableListOf<Operation>()
            fun dispatch(op: Operation) = repository.dispatch(op).also { ops += op }
        }

        fun form(
            repository: BandRepository,
            route: CostRoute,
            recorder: Recorder = Recorder(repository),
            handle: SavedStateHandle = SavedStateHandle(),
        ) = CostViewModel(route, repository.state, recorder::dispatch, { _, _ -> }, emptyList(), handle, today) { it.toString() }

        expect("a new cost is the user's own today and saves once the amount is typed") {
            val repository = band(coroutineTestScope)
            val form = form(repository, CostRoute())
            form.draft.payerId shouldBe me(repository)
            form.draft.date shouldBe today
            withClue("no amount yet") { form.state.canSave shouldBe false }
            withClue("her events, newest first") { form.state.events shouldBe listOf(null, "solo", "gig") }
            form.amount("12.50")
            form.category(CostCategory.TRAVEL)
            form.event("gig")
            form.state.canSave shouldBe true
            val outcome = form.save().shouldBeInstanceOf<CostOutcome.Added>()
            outcome.forOther shouldBe false
            val cost = ready(repository).band.cost(outcome.costId)!!
            cost.amount shouldBe Money.parse("12.50")
            cost.eventId shouldBe "gig"
            cost.category shouldBe CostCategory.TRAVEL
            withClue("once only: a second tap must not add it again") { form.save().shouldBeNull() }
        }

        expect("a lead may pay for someone else and is told so") {
            val repository = band(coroutineTestScope)
            val form = form(repository, CostRoute())
            form.state.payers.map { it.id } shouldBe listOf(me(repository), "bob")
            form.payer("bob")
            form.amount("5")
            form.save().shouldBeInstanceOf<CostOutcome.Added>().forOther shouldBe true
        }

        expect("another payer drops an event they are not in") {
            val repository = band(coroutineTestScope)
            val form = form(repository, CostRoute())
            form.event("solo")
            form.payer("bob")
            withClue("Bob does not play Solo") { form.draft.eventId.shouldBeNull() }
            form.state.events shouldBe listOf(null, "gig")
        }

        expect("opened from an event the event is fixed and only its players paid") {
            val repository = band(coroutineTestScope)
            val form = form(repository, CostRoute(forEventId = "solo"))
            form.state.forEvent?.id shouldBe "solo"
            form.draft.eventId shouldBe "solo"
            withClue("nothing to choose") { form.state.events.shouldBeEmpty() }
            form.state.payers.map { it.id } shouldBe listOf(me(repository))
        }

        expect("editing sends only what changed since the form opened") {
            val repository = band(coroutineTestScope)
            repository.dispatch(AddCost("c1", me(repository), Money.parse("20"), today, "gig", CostCategory.FOOD, "Pizza")).shouldBeNull()
            val recorder = Recorder(repository)
            val form = form(repository, CostRoute("c1"), recorder)
            form.draft.amountText shouldBe "20.00"
            form.state.isNew shouldBe false
            form.description("Pizza and drinks")
            form.save().shouldBeInstanceOf<CostOutcome.Updated>()
            val sent = recorder.ops.single().shouldBeInstanceOf<UpdateCost>()
            sent.description?.value shouldBe "Pizza and drinks"
            withClue("untouched fields are not sent, so another device's change to them survives") { sent.amount.shouldBeNull() }
            sent.date.shouldBeNull()
            sent.category.shouldBeNull()
        }

        expect("a change made elsewhere while the form is open is not overwritten") {
            val repository = band(coroutineTestScope)
            repository.dispatch(AddCost("c1", me(repository), Money.parse("20"), today, description = "Pizza")).shouldBeNull()
            val recorder = Recorder(repository)
            val form = form(repository, CostRoute("c1"), recorder)
            // Another device corrected the amount meanwhile (synced in while the form was open).
            repository.dispatch(UpdateCost("c1", amount = Change(Money.parse("30")))).shouldBeNull()
            form.description("Pizza and drinks")
            form.save().shouldBeInstanceOf<CostOutcome.Updated>()
            val sent = recorder.ops.single().shouldBeInstanceOf<UpdateCost>()
            withClue("the untouched amount is not sent, so the correction survives") { sent.amount.shouldBeNull() }
            ready(repository).band.cost("c1")!!.amount shouldBe Money.parse("30")
            ready(repository).band.cost("c1")!!.description shouldBe "Pizza and drinks"
        }

        expect("deleting asks first then goes back") {
            val repository = band(coroutineTestScope)
            repository.dispatch(AddCost("c1", me(repository), Money.parse("20"), today)).shouldBeNull()
            val form = form(repository, CostRoute("c1"))
            form.state.canDelete shouldBe true
            form.askDelete()
            form.dialog shouldBe CostDialog.CONFIRM_DELETE
            form.delete().shouldBeInstanceOf<CostOutcome.Deleted>()
            form.dialog.shouldBeNull()
            ready(repository).band.cost("c1").shouldBeNull()
        }

        expect("a cost that is gone is not found") {
            val form = form(band(coroutineTestScope), CostRoute("nope"))
            form.state.notFound shouldBe true
            form.amount("5")
            form.state.canSave shouldBe false
        }
    })
