package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** The dialogs over an event's collected money: which is open, with what form. */
class CollectedDialogsTest :
    ExpectSpec({
        coroutineTestScope = true
        var nextId = 0

        fun band(repository: BandRepository): Band = repository.state.value.shouldBeInstanceOf<BandState.Ready>().band

        /** Alice (owner, the user) and Bob, both playing the gig. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            val alice = band(repository).members.single().id
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", alice)).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "bob")).shouldBeNull()
            return repository
        }

        expect("one dialog over the collected money at a time, each with its own form") {
            val repository = repository(coroutineTestScope)
            val alice = band(repository).members.single { it.name == "Alice" }.id
            repository.dispatch(AddHolding("gig", "fee", ORGANISER, Money.parse("2000"), IncomeSource.CONTRACT)).shouldBeNull()
            val fee = band(repository).event("gig")!!.holdings.single()
            val dialogs = CollectedDialogs("gig")
            val text: (Money) -> String = { it.toString() }
            dialogs.open.shouldBeNull()

            dialogs.addEntry(alice, listOf(alice), text)
            val entry = dialogs.open.shouldBeInstanceOf<CollectedDialog.Entry>().form
            entry.isNew shouldBe true
            entry.partyId shouldBe alice

            dialogs.addAwaited(text)
            val awaited = dialogs.open.shouldBeInstanceOf<CollectedDialog.Entry>().form
            awaited.partyId shouldBe ORGANISER
            awaited.choosesParty shouldBe false

            dialogs.editEntry(fee, listOf(ORGANISER), text)
            dialogs.open.shouldBeInstanceOf<CollectedDialog.Entry>().form.initial shouldBe fee

            dialogs.receive(fee, listOf(alice), me = alice, text)
            val receive = dialogs.open.shouldBeInstanceOf<CollectedDialog.Receive>().form
            receive.awaited shouldBe fee
            receive.partyId shouldBe alice

            dialogs.welch(fee)
            dialogs.open shouldBe CollectedDialog.Welching(fee)

            dialogs.dismiss()
            dialogs.open.shouldBeNull()
        }

        expect("files shared while an entry is open start it again with them") {
            val repository = repository(coroutineTestScope)
            val alice = band(repository).members.single { it.name == "Alice" }.id
            val dialogs = CollectedDialogs("gig")
            val text: (Money) -> String = { it.toString() }
            dialogs.addEntry(alice, listOf(alice), text)
            val first = dialogs.open.shouldBeInstanceOf<CollectedDialog.Entry>().form
            first.amount.typeGross("150")

            val contract = PreparedFile(AttachmentKind.PDF, "contract.pdf", 10, "")
            dialogs.addEntry(alice, listOf(alice), text, drafts = listOf(contract))
            val second = dialogs.open.shouldBeInstanceOf<CollectedDialog.Entry>().form
            second shouldNotBe first
            second.drafts shouldBe listOf(contract)
            withClue("what was typed before does not carry over") { second.amount.grossText shouldBe "" }
        }
    })
