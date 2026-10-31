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
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
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

/** The band fund's entry form (ITERATION_7 §5.1), without a dialog. */
class FundEntryFormTest {
    private val today = LocalDate(2026, 10, 31)
    private var nextId = 0

    /** Alice (owner, the user, the fund's holder), Bob, Carol; Bob's cost of 40 with nothing refunded yet. */
    private suspend fun TestScope.repository(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        assertNull(repository.dispatch(AddMember("carol", "Carol")))
        assertNull(repository.dispatch(SetTreasurer(me(repository))))
        assertNull(repository.dispatch(AddCost("c1", "bob", Money.parse("40"), today, description = "Cable")))
        return repository
    }

    private fun me(repository: BandRepository) = ready(repository).me!!.id

    private fun ready(repository: BandRepository) = assertIs<BandState.Ready>(repository.state.value)

    private fun band(repository: BandRepository): Band = ready(repository).band

    private fun form(repository: BandRepository, refund: String? = null, initialId: String? = null) = FundEntryForm(
        band(repository),
        initial = initialId?.let { id -> band(repository).fundEntries.first { it.id == id } },
        me = me(repository),
        refundsCosts = true,
        today = today,
        amountText = { it.toString() },
        costName = { it.description ?: "Cost" },
        refund = refund,
    )

    @Test
    fun moneyInIsFromTheUserByDefaultAndNeedsAnAmountAndADescription() = runTest {
        val repository = repository()
        val form = form(repository)
        assertTrue(form.incoming)
        assertEquals(listOf(me(repository)), form.payers)
        assertFalse(form.ready)
        form.amount = "50"
        assertFalse(form.ready, "a description too")
        form.description = "October fee"
        assertTrue(form.ready)
        assertNull(form.save { repository.dispatch(it) })
        val entry = band(repository).fundEntries.single()
        assertEquals(Money.parse("50"), entry.amount)
        assertEquals(me(repository), entry.paidBy)
    }

    @Test
    fun severalPeopleAreOneEntryEachAndARefusalKeepsTheRest() = runTest {
        val repository = repository()
        val form = form(repository)
        form.amount = "20"
        form.description = "Fee"
        form.choosePeople(setOf("bob", "carol", me(repository)))
        assertEquals(listOf(me(repository), "bob", "carol"), form.payers, "in the people's order")
        assertEquals(3, form.operations().size)
        // Bob's entry is refused (e.g. the server's limit): Alice's went through, Bob and Carol stay ticked.
        val refusal = Rejection(RejectionCode.INVALID, "no")
        val refused = form.save { op -> if ((op as AddFundEntry).paidBy == "bob") refusal else repository.dispatch(op) }
        assertEquals(refusal, refused)
        assertEquals(listOf("bob", "carol"), form.payers)
        assertEquals(1, band(repository).fundEntries.size)
        assertNull(form.save { repository.dispatch(it) })
        assertEquals(3, band(repository).fundEntries.size)
    }

    @Test
    fun aRefundTakesTheCostsOutstandingAmountAndNameUnlessTypedOver() = runTest {
        val repository = repository()
        val form = form(repository, refund = "c1")
        assertFalse(form.incoming)
        assertEquals("40.00", form.amount)
        assertEquals("Cable", form.description)
        assertTrue(form.ready)
        form.description = "Bob's cable"
        assertNull(repository.dispatch(AddCost("c2", "carol", Money.parse("15"), today, description = "Strings")))
        form.pickCost(band(repository), "c2")
        assertEquals("15.00", form.amount)
        assertEquals("Bob's cable", form.description, "what the user typed stays")
        assertNull(form.save { repository.dispatch(it) })
        val entry = band(repository).fundEntries.single()
        assertEquals(Money.parse("-15"), entry.amount)
        assertEquals("c2", entry.costId)
    }

    @Test
    fun aRefundNeedsACost() = runTest {
        val repository = repository()
        val form = form(repository)
        form.incoming = false
        form.amount = "10"
        form.description = "Something"
        assertFalse(form.ready)
        assertEquals(listOf("c1"), form.pendingCosts.map { it.id })
        form.pickCost(band(repository), "c1")
        assertTrue(form.ready)
    }

    @Test
    fun editingSendsOnlyTheChangedFields() = runTest {
        val repository = repository()
        assertNull(repository.dispatch(AddFundEntry("e1", today, "Fee", Money.parse("50"), paidBy = "bob")))
        val form = form(repository, initialId = "e1")
        assertFalse(form.isNew)
        assertEquals("50.00", form.amount)
        assertEquals(listOf("bob"), form.payers)
        form.choosePeople(setOf("carol"))
        val sent = mutableListOf<Operation>()
        assertNull(
            form.save {
                sent += it
                repository.dispatch(it)
            },
        )
        val update = assertIs<UpdateFundEntry>(sent.single())
        assertEquals("carol", assertNotNull(update.paidBy).value)
        assertNull(update.amount)
        assertNull(update.description)
        assertNull(update.date)
    }
}
