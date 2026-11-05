package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.view.HandOver
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** The hand-over form, without a dialog. */
class HandOverFormTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        fun event(repository: BandRepository): Event = ready(repository).band.event("gig")!!

        fun handOver(repository: BandRepository): HandOver {
            val ready = ready(repository)
            val event = event(repository)
            return HandOver(ready.view, event, ready.view.settlement(event), Capabilities(ready.band, ready.userId))
        }

        fun open(repository: BandRepository): HandOverForm? {
            val handOver = handOver(repository)
            return HandOverForm.open("gig", handOver, me(repository), amountText = { it.toString() })
        }

        /** Alice (owner, the user) and Bob, both playing the gig; Alice holds 300 unless [holding] is false. */
        suspend fun repository(scope: CoroutineScope, holding: Boolean = true): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", me(repository))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "bob")).shouldBeNull()
            if (holding) {
                repository.dispatch(AddHolding("gig", "h", me(repository), Money.parse("300"), IncomeSource.HAT)).shouldBeNull()
            }
            return repository
        }

        expect("opens on the user holding money, offering what they hold") {
            val repository = repository(coroutineTestScope)
            val alice = me(repository)
            val form = open(repository).shouldNotBeNull()
            form.from shouldBe alice
            form.to shouldBe "bob"
            form.amount.grossText shouldBe "300.00"
            form.held shouldBe Money.parse("300")
            form.ready shouldBe true

            val sent = form.operation()
            sent.eventId shouldBe "gig"
            sent.from shouldBe alice
            sent.to shouldBe "bob"
            sent.amount shouldBe Money.parse("300")
            sent.note.shouldBeNull()
        }

        expect("no more than is held, and not to oneself") {
            val repository = repository(coroutineTestScope)
            val form = open(repository).shouldNotBeNull()
            form.amount.typeGross("300.01")
            form.ready shouldBe false

            form.amount.typeGross("150")
            form.note = "  "
            form.ready shouldBe true
            form.operation().amount shouldBe Money.parse("150")
            form.operation().note.shouldBeNull()

            form.note = "half"
            form.operation().note shouldBe "half"

            form.to = form.from
            form.ready shouldBe false
        }

        expect("another sender takes their own recipients and holding") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(AddMember("carol", "Carol")).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "carol")).shouldBeNull()
            repository.dispatch(AddHolding("gig", "h2", "carol", Money.parse("100"), IncomeSource.HAT)).shouldBeNull()
            val form = open(repository).shouldNotBeNull()
            form.senders shouldContainExactlyInAnyOrder listOf(me(repository), "carol")

            form.sender("carol")
            form.from shouldBe "carol"
            form.to shouldBe "bob"
            form.recipients.contains("carol") shouldBe false
            form.amount.grossText shouldBe "100.00"
            form.held shouldBe Money.parse("100")
        }

        expect("nobody to give or receive: no form") {
            val repository = repository(coroutineTestScope, holding = false)
            open(repository).shouldBeNull()
        }

        expect("saving goes through the band") {
            val repository = repository(coroutineTestScope)
            val form = open(repository).shouldNotBeNull()
            repository.dispatch(form.operation()).shouldBeNull()

            val transfer = event(repository).transfers.single()
            transfer.from shouldBe me(repository)
            transfer.to shouldBe "bob"
            transfer.amount shouldBe Money.parse("300")
            handOver(repository).senders shouldBe listOf("bob")
        }
    })
