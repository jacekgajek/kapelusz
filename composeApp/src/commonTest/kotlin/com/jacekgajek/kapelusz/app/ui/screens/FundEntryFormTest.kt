package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** The band fund's entry form, without a dialog. */
class FundEntryFormTest :
    ExpectSpec({
        coroutineTestScope = true
        val today = LocalDate(2026, 10, 31)
        var nextId = 0

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        fun band(repository: BandRepository): Band = ready(repository).band

        /** Alice (owner, the user, the fund's holder), Bob, Carol; Bob's cost of 40 with nothing refunded yet. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(AddMember("carol", "Carol")).shouldBeNull()
            repository.dispatch(SetTreasurer(me(repository))).shouldBeNull()
            repository.dispatch(AddCost("c1", "bob", Money.parse("40"), today, description = "Cable")).shouldBeNull()
            return repository
        }

        fun form(repository: BandRepository, refund: String? = null, initialId: String? = null) = FundEntryForm(
            band(repository),
            initial = initialId?.let { id -> band(repository).fundEntries.first { it.id == id } },
            me = me(repository),
            refundsCosts = true,
            today = today,
            amountText = { it.toString() },
            costName = { it.description ?: "Cost" },
            refund = refund,
        )

        expect("money in is from the user by default and needs an amount and a description") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)
            form.incoming shouldBe true
            form.payers shouldBe listOf(me(repository))
            form.ready shouldBe false
            form.amount = "50"
            withClue("a description too") { form.ready shouldBe false }
            form.description = "October fee"
            form.ready shouldBe true
            form.save { repository.dispatch(it) }.shouldBeNull()
            val entry = band(repository).fundEntries.single()
            entry.amount shouldBe Money.parse("50")
            entry.paidBy shouldBe me(repository)
        }

        expect("several people are one entry each and a refusal keeps the rest") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)
            form.amount = "20"
            form.description = "Fee"
            form.choosePeople(band(repository), setOf("bob", "carol", me(repository)))
            withClue("in the people's order") { form.payers shouldBe listOf(me(repository), "bob", "carol") }
            form.operations() shouldHaveSize 3
            // Bob's entry is refused (e.g. the server's limit): Alice's went through, Bob and Carol stay ticked.
            val refusal = Rejection(RejectionCode.INVALID, "no")
            val refused = form.save { op -> if ((op as AddFundEntry).paidBy == "bob") refusal else repository.dispatch(op) }
            refused shouldBe refusal
            form.payers shouldBe listOf("bob", "carol")
            band(repository).fundEntries shouldHaveSize 1
            form.save { repository.dispatch(it) }.shouldBeNull()
            band(repository).fundEntries shouldHaveSize 3
        }

        expect("a refund takes the cost's outstanding amount and name unless typed over") {
            val repository = repository(coroutineTestScope)
            val form = form(repository, refund = "c1")
            form.incoming shouldBe false
            form.amount shouldBe "40.00"
            form.description shouldBe "Cable"
            form.ready shouldBe true
            repository.dispatch(AddCost("c2", "carol", Money.parse("15"), today, description = "Strings")).shouldBeNull()
            form.pickCost(band(repository), "c2")
            form.amount shouldBe "15.00"
            withClue("the name filled in follows the cost picked") { form.description shouldBe "Strings" }
            form.description = "Carol's strings"
            form.pickCost(band(repository), "c1")
            form.amount shouldBe "40.00"
            withClue("what the user typed stays") { form.description shouldBe "Carol's strings" }
            form.pickCost(band(repository), "c2")
            form.save { repository.dispatch(it) }.shouldBeNull()
            val entry = band(repository).fundEntries.single()
            entry.amount shouldBe Money.parse("-15")
            entry.costId shouldBe "c2"
        }

        expect("a refund needs a cost") {
            val repository = repository(coroutineTestScope)
            val form = form(repository)
            form.incoming = false
            form.amount = "10"
            form.description = "Something"
            form.ready shouldBe false
            form.pendingCosts(band(repository)).map { it.id } shouldBe listOf("c1")
            form.pickCost(band(repository), "c1")
            form.ready shouldBe true
        }

        expect("editing sends only the changed fields") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(AddFundEntry("e1", today, "Fee", Money.parse("50"), paidBy = "bob")).shouldBeNull()
            val form = form(repository, initialId = "e1")
            form.isNew shouldBe false
            form.amount shouldBe "50.00"
            form.payers shouldBe listOf("bob")
            form.choosePeople(band(repository), setOf("carol"))
            val sent = mutableListOf<Operation>()
            form.save {
                sent += it
                repository.dispatch(it)
            }.shouldBeNull()
            val update = sent.single().shouldBeInstanceOf<UpdateFundEntry>()
            update.paidBy.shouldNotBeNull().value shouldBe "carol"
            update.amount.shouldBeNull()
            update.description.shouldBeNull()
            update.date.shouldBeNull()
        }
    })
