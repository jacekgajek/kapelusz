package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.InMemoryStore
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.SetTreasurer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** The fund tab's dialog cell: when the entry form opens and when it closes. */
class FundEntryViewModelTest {
    private val today = LocalDate(2026, 10, 31)
    private var nextId = 0

    private suspend fun TestScope.repository(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        assertNull(repository.dispatch(SetTreasurer(me(repository))))
        return repository
    }

    private fun me(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value).me!!.id

    private fun band(repository: BandRepository): Band = assertIs<BandState.Ready>(repository.state.value).band

    private fun FundEntryViewModel.open(repository: BandRepository, refund: String? = null) {
        val amountText = { money: Money -> money.toString() }
        add(band(repository), me(repository), refundsCosts = true, today, amountText, { it.description ?: "Cost" }, refund)
    }

    @Test
    fun saveClosesTheFormWhenAllWentThroughAndKeepsItOnARefusal() = runTest {
        val repository = repository()
        val entries = FundEntryViewModel()
        entries.open(repository)
        val form = assertNotNull(entries.form)
        assertNull(entries.save { repository.dispatch(it) }, "not ready: nothing happens")
        assertNotNull(entries.form, "and the form stays")
        form.amount = "10"
        form.description = "Fee"
        val refusal = Rejection(RejectionCode.FORBIDDEN, "no")
        assertEquals(refusal, entries.save { refusal })
        assertNotNull(entries.form, "still open to retry")
        assertNull(entries.save { repository.dispatch(it) })
        assertNull(entries.form)
        assertEquals(1, band(repository).fundEntries.size)
    }

    @Test
    fun deleteClosesTheFormOfAnExistingEntry() = runTest {
        val repository = repository()
        assertNull(repository.dispatch(AddFundEntry("e1", today, "Fee", Money.parse("10"), paidBy = me(repository))))
        val entries = FundEntryViewModel()
        entries.edit(band(repository), band(repository).fundEntries.single(), me(repository), today, { it.toString() }, { "Cost" })
        assertFalse(assertNotNull(entries.form).isNew)
        assertNull(entries.delete { repository.dispatch(it) })
        assertNull(entries.form)
        assertTrue(band(repository).fundEntries.isEmpty())
    }

    @Test
    fun aCostAddedFromHereComesBackAsARefundOnlyWhileSomethingIsLeftToRefund() = runTest {
        val repository = repository()
        assertNull(repository.dispatch(AddCost("c1", me(repository), Money.parse("25"), today)))
        val entries = FundEntryViewModel()
        entries.refundAddedCost(band(repository), "c1", me(repository), refundsCosts = true, today, { it.toString() }, { "Cost" })
        val form = assertNotNull(entries.form)
        assertFalse(form.incoming)
        assertEquals("c1", form.costId)
        assertEquals("25.00", form.amount)
        entries.dismiss()
        assertNull(repository.dispatch(AddFundEntry("e1", today, "Cost", Money.parse("-25"), costId = "c1")))
        entries.refundAddedCost(band(repository), "c1", me(repository), refundsCosts = true, today, { it.toString() }, { "Cost" })
        assertNull(entries.form, "refunded in full already")
    }
}
