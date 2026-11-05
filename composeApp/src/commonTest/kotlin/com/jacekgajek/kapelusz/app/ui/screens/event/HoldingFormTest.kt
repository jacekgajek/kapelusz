package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UpdateHolding
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** The collected money form, without a dialog. */
class HoldingFormTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        fun event(repository: BandRepository): Event = ready(repository).band.event("gig")!!

        fun holding(repository: BandRepository): Holding = event(repository).holdings.single()

        /** Alice (owner, the user) and Bob, both playing the gig. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", me(repository))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "bob")).shouldBeNull()
            return repository
        }

        fun run(repository: BandRepository): (Operation) -> Boolean = { repository.dispatch(it) == null }

        /** Files attached after a save: the holding they went to, if any. */
        class Attached {
            var to: String? = null
            var files: List<PreparedFile> = emptyList()
            val attach: (String, List<PreparedFile>) -> Unit = { id, files ->
                to = id
                this.files = files
            }
        }

        fun form(
            repository: BandRepository,
            initial: Holding? = null,
            defaultParty: String = me(repository),
            parties: List<String> = listOf(me(repository), "bob"),
            drafts: List<PreparedFile> = emptyList(),
        ) = HoldingForm("gig", initial, defaultParty, parties, amountText = { it.toString() }, initialDrafts = drafts)

        expect("a new entry is the user's hat money, needs an amount, and takes its files along") {
            val repository = repository(coroutineTestScope)
            val photo = PreparedFile(AttachmentKind.IMAGE, "door.jpg", 10, "")
            val form = form(repository, drafts = listOf(photo))
            form.isNew shouldBe true
            form.choosesParty shouldBe true
            form.source shouldBe IncomeSource.HAT
            form.ready shouldBe false

            form.amount.text = "150"
            form.ready shouldBe true

            form.partyId = "bob"
            form.note = " hat round "
            val attached = Attached()
            form.save(run(repository), attached.attach) shouldBe true

            val holding = holding(repository)
            holding.partyId shouldBe "bob"
            holding.amount shouldBe Money.parse("150")
            holding.source shouldBe IncomeSource.HAT
            holding.note shouldBe "hat round"
            attached.to shouldBe holding.id
            attached.files shouldBe listOf(photo)
        }

        expect("an awaited fee is the organiser's contract money, with nobody else to pick") {
            val repository = repository(coroutineTestScope)
            val form = form(repository, defaultParty = ORGANISER, parties = listOf(ORGANISER))
            form.choosesParty shouldBe false
            form.source shouldBe IncomeSource.CONTRACT

            form.amount.text = "2000"
            form.save(run(repository), Attached().attach) shouldBe true
            holding(repository).awaited shouldBe true
        }

        expect("saving unready is a bug, not a no-op") {
            val repository = repository(coroutineTestScope)
            shouldThrow<IllegalStateException> { form(repository).save(run(repository), Attached().attach) }
        }

        expect("a refusal keeps the files for a retry") {
            val repository = repository(coroutineTestScope)
            val form = form(repository, drafts = listOf(PreparedFile(AttachmentKind.PDF, "contract.pdf", 10, "")))
            form.amount.text = "150"
            val attached = Attached()
            // The band refused it (e.g. the server's limit), which the snackbar told: nothing was added.
            form.save({ false }, attached.attach) shouldBe false
            attached.to.shouldBeNull()
            event(repository).holdings.shouldBeEmpty()
        }

        expect("an edit opens as it is and sends only what changed") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(AddHolding("gig", "h1", "bob", Money.parse("150"), IncomeSource.DOOR, "round one")).shouldBeNull()
            val form = form(repository, initial = holding(repository), defaultParty = "bob")
            form.isNew shouldBe false
            form.choosesParty shouldBe false
            form.amount.text shouldBe "150.00"
            form.source shouldBe IncomeSource.DOOR
            form.note shouldBe "round one"

            form.amount.text = "175"
            form.note = "round one "
            val sent = mutableListOf<Operation>()
            form.save({ sent.add(it) && repository.dispatch(it) == null }, Attached().attach) shouldBe true

            withClue("an unchanged source and a note only padded are not changes") {
                sent.single() shouldBe UpdateHolding("gig", "h1", amount = Change(Money.parse("175")))
            }
            holding(repository).amount shouldBe Money.parse("175")
        }

        expect("delete removes an existing entry and is nothing for a new one") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(AddHolding("gig", "h1", "bob", Money.parse("150"), IncomeSource.HAT)).shouldBeNull()
            val form = form(repository, initial = holding(repository), defaultParty = "bob")
            form.confirmingDelete = true
            form.delete(run(repository)) shouldBe true

            form.confirmingDelete shouldBe false
            event(repository).holdings.shouldBeEmpty()
            form(repository).delete(run(repository)) shouldBe false
        }
    })
