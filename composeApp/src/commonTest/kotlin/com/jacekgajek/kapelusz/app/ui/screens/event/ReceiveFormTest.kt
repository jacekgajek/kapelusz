package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** The "Received…" form of an awaited fee, without a dialog. */
class ReceiveFormTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        fun event(repository: BandRepository): Event = ready(repository).band.event("gig")!!

        fun awaited(repository: BandRepository): Holding = event(repository).holdings.single { it.awaited }

        /** Alice (owner, the user) and Bob, both playing the gig; the organiser owes 2000 for it. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", me(repository))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "bob")).shouldBeNull()
            repository.dispatch(AddHolding("gig", "fee", ORGANISER, Money.parse("2000"), IncomeSource.CONTRACT)).shouldBeNull()
            return repository
        }

        fun run(repository: BandRepository): (Operation) -> Boolean = { repository.dispatch(it) == null }

        fun form(repository: BandRepository, me: String? = me(repository)): ReceiveForm {
            val parties = listOf(me(repository), "bob")
            return ReceiveForm("gig", awaited(repository), parties, me, amountText = { it.toString() })
        }

        expect("opens on the user with the whole fee, and records it as theirs") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)
            form.partyId shouldBe me(repository)
            form.amount.grossText shouldBe "2000.00"
            form.ready shouldBe true

            form.partyId = "bob"
            form.save(run(repository)) shouldBe true

            val received = event(repository).holdings.single()
            received.partyId shouldBe "bob"
            received.amount shouldBe Money.parse("2000")
            received.source shouldBe IncomeSource.CONTRACT
        }

        expect("falls back to the first party when the user may not receive") {
            val repository = repository(coroutineTestScope)
            form(repository, me = null).partyId shouldBe me(repository)
        }

        expect("no more than was awaited can come in") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)

            form.amount.typeGross("2000.01")
            form.ready shouldBe false
            shouldThrow<IllegalStateException> { form.save(run(repository)) }

            form.amount.typeGross("0")
            form.ready shouldBe false
        }

        expect("part of it leaves the rest awaited") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)

            form.amount.typeGross("500")
            form.ready shouldBe true
            form.save(run(repository)) shouldBe true

            event(repository).holdings shouldHaveSize 2
            awaited(repository).amount shouldBe Money.parse("1500")
            event(repository).holdings.single { !it.awaited }.amount shouldBe Money.parse("500")
        }
    })
