package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.view.HandOver
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** What is open over an event's transfers. */
class TransfersDialogsTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        fun handOver(repository: BandRepository): HandOver {
            val ready = ready(repository)
            val event = ready.band.event("gig")!!
            return HandOver(ready.view, event, ready.view.settlement(event), Capabilities(ready.band, ready.userId))
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

        expect("one dialog at a time") {
            val repository = repository(coroutineTestScope)
            val alice = me(repository)
            val dialogs = TransfersDialogs("gig")
            dialogs.open.shouldBeNull()

            dialogs.handOver(handOver(repository), alice) { it.toString() }
            dialogs.open.shouldBeInstanceOf<TransfersDialog.HandingOver>().form.from shouldBe alice

            val transfer = Transfer("t1", alice, "bob", Money.parse("100"))
            dialogs.remove(transfer)
            dialogs.open shouldBe TransfersDialog.Removing(transfer)

            dialogs.dismiss()
            dialogs.open.shouldBeNull()
        }

        expect("a hand-over with nobody to give opens nothing") {
            val repository = repository(coroutineTestScope, holding = false)
            val dialogs = TransfersDialogs("gig")
            dialogs.handOver(handOver(repository), me(repository)) { it.toString() }
            dialogs.open.shouldBeNull()
        }
    })
