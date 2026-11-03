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
import com.jacekgajek.kapelusz.ops.SetTreasurer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** The fund tab's dialog cell: when the entry form opens and when it closes. */
class FundEntryViewModelTest :
    ExpectSpec({
        coroutineTestScope = true
        val today = LocalDate(2026, 10, 31)
        var nextId = 0

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        fun me(repository: BandRepository) = ready(repository).me!!.id

        fun band(repository: BandRepository): Band = ready(repository).band

        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(SetTreasurer(me(repository))).shouldBeNull()
            return repository
        }

        fun FundEntryViewModel.open(repository: BandRepository, refund: String? = null) {
            val amountText = { money: Money -> money.toString() }
            add(band(repository), me(repository), refundsCosts = true, today, amountText, { it.description ?: "Cost" }, refund)
        }

        expect("save closes the form when all went through and keeps it on a refusal") {
            val repository = repository(coroutineTestScope)
            val entries = FundEntryViewModel()
            entries.open(repository)
            val form = entries.form.shouldNotBeNull()
            withClue("not ready: nothing happens") { entries.save { repository.dispatch(it) }.shouldBeNull() }
            withClue("and the form stays") { entries.form.shouldNotBeNull() }
            form.amount = "10"
            form.description = "Fee"
            val refusal = Rejection(RejectionCode.FORBIDDEN, "no")
            entries.save { refusal } shouldBe refusal
            withClue("still open to retry") { entries.form.shouldNotBeNull() }
            entries.save { repository.dispatch(it) }.shouldBeNull()
            entries.form.shouldBeNull()
            band(repository).fundEntries shouldHaveSize 1
        }

        expect("delete closes the form of an existing entry") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(AddFundEntry("e1", today, "Fee", Money.parse("10"), paidBy = me(repository))).shouldBeNull()
            val entries = FundEntryViewModel()
            entries.edit(band(repository), band(repository).fundEntries.single(), me(repository), today, { it.toString() }, { "Cost" })
            entries.form.shouldNotBeNull().isNew shouldBe false
            entries.delete { repository.dispatch(it) }.shouldBeNull()
            entries.form.shouldBeNull()
            band(repository).fundEntries.shouldBeEmpty()
        }

        expect("a cost added from here comes back as a refund only while something is left to refund") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(AddCost("c1", me(repository), Money.parse("25"), today)).shouldBeNull()
            val entries = FundEntryViewModel()
            entries.refundAddedCost(band(repository), "c1", me(repository), refundsCosts = true, today, { it.toString() }, { "Cost" })
            val form = entries.form.shouldNotBeNull()
            form.incoming shouldBe false
            form.costId shouldBe "c1"
            form.amount shouldBe "25.00"
            entries.dismiss()
            repository.dispatch(AddFundEntry("e1", today, "Cost", Money.parse("-25"), costId = "c1")).shouldBeNull()
            entries.refundAddedCost(band(repository), "c1", me(repository), refundsCosts = true, today, { it.toString() }, { "Cost" })
            withClue("refunded in full already") { entries.form.shouldBeNull() }
        }
    })
