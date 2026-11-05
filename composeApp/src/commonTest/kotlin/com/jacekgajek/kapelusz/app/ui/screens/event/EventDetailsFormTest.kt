package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import com.jacekgajek.kapelusz.ops.UpdateEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The "When and where" form, without a dialog. */
class EventDetailsFormTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        fun band(repository: BandRepository): Band = repository.state.value.shouldBeInstanceOf<BandState.Ready>().band

        fun event(repository: BandRepository): Event = band(repository).event("gig")!!

        /** Alice (owner, the user) and Bob; a gig at Klub on 2026-09-13 with no agenda. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(UpdateEvent("gig", location = Change("Klub"))).shouldBeNull()
            return repository
        }

        suspend fun withTemplate(repository: BandRepository) {
            val items = listOf(AgendaTemplateItem(AgendaKind.ARRIVAL), AgendaTemplateItem(AgendaKind.CONCERT_START))
            repository.dispatch(SetAgendaTemplate(items)).shouldBeNull()
        }

        fun run(repository: BandRepository): (Operation) -> Boolean = { repository.dispatch(it) == null }

        fun form(repository: BandRepository) = EventDetailsForm(event(repository), band(repository))

        expect("opens as the event is, with the band's template times unset") {
            val repository = repository(coroutineTestScope)
            withTemplate(repository)

            val form = form(repository)
            form.fields.name shouldBe "Gig"
            form.fields.date shouldBe LocalDate(2026, 9, 13)
            form.fields.location shouldBe "Klub"
            form.fields.agenda.map { it.kind } shouldBe listOf(AgendaKind.ARRIVAL, AgendaKind.CONCERT_START)
            form.fields.agenda.all { it.time == null && it.fromTemplate } shouldBe true
            form.operations().shouldBeEmpty()
            form.ready shouldBe true
        }

        expect("sends only what changed") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)

            form.fields = form.fields.copy(name = "Gig 2 ", location = "")
            val operations = form.operations()
            val update = operations.single().shouldBeInstanceOf<UpdateEvent>()
            update.name shouldBe Change("Gig 2 ")
            update.date.shouldBeNull()
            update.location shouldBe Change(null)

            form.save(run(repository)) shouldBe true
            event(repository).name shouldBe "Gig 2"
            event(repository).location.shouldBeNull()
        }

        expect("template rows left untimed are no agenda change; a time set is") {
            val repository = repository(coroutineTestScope)
            withTemplate(repository)
            val form = form(repository)
            form.operations().shouldBeEmpty()

            val timed = form.fields.agenda.mapIndexed { index, row -> if (index == 0) row.copy(time = LocalTime(20, 0)) else row }
            form.fields = form.fields.copy(agenda = timed)
            form.operations().single().shouldBeInstanceOf<SetEventAgenda>().agenda shouldBe timed

            form.save(run(repository)) shouldBe true
            event(repository).agenda.first().time shouldBe LocalTime(20, 0)
        }

        expect("a blank name is not ready") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)

            form.fields = form.fields.copy(name = " ")
            form.ready shouldBe false
            shouldThrow<IllegalStateException> { form.save(run(repository)) }
        }

        expect("a refusal stops at the first operation and keeps the form") {
            val repository = repository(coroutineTestScope)
            withTemplate(repository)
            val form = form(repository)
            val timed = form.fields.agenda.mapIndexed { index, row -> if (index == 0) row.copy(time = LocalTime(20, 0)) else row }
            form.fields = form.fields.copy(name = "Gig 2", agenda = timed)
            form.operations().size shouldBe 2

            val attempted = mutableListOf<Operation>()
            val refuse: (Operation) -> Boolean = { operation ->
                attempted += operation
                operation !is UpdateEvent
            }
            form.save(refuse) shouldBe false
            attempted.size shouldBe 1
            form.fields.name shouldBe "Gig 2"
        }
    })
