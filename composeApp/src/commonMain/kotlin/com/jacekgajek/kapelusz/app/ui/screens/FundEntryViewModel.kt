package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.settlement.CostLedger
import com.jacekgajek.kapelusz.text.sortedByName
import kotlin.math.abs
import kotlinx.datetime.LocalDate

/**
 * The band fund tab's state that outlives a recomposition (I7 §5.1): the entry dialog open on it, if any, as
 * a [FundEntryForm]. Scoped to the tab's back-stack entry.
 */
class FundEntryViewModel : ViewModel() {
    /** The dialog's form while one is open. */
    var form by mutableStateOf<FundEntryForm?>(null)
        private set

    /** Opens a new entry; with [refund], the refund of that cost (just added with the +, or picked). */
    fun add(
        band: Band,
        me: String?,
        refundsCosts: Boolean,
        today: LocalDate,
        amountText: (Money) -> String,
        costName: (Cost) -> String,
        refund: String? = null,
    ) {
        form = FundEntryForm(band, initial = null, me, refundsCosts, today, amountText, costName, refund)
    }

    fun edit(band: Band, entry: FundEntry, me: String?, today: LocalDate, amountText: (Money) -> String, costName: (Cost) -> String) {
        form = FundEntryForm(band, entry, me, refundsCosts = true, today, amountText, costName)
    }

    fun dismiss() {
        form = null
    }

    /**
     * A cost just added from here (the + beside the costs to refund) comes back to be refunded in a new entry; not
     * when there is nothing left to refund (e.g. a lead's cost for an event, refunded there at once).
     */
    fun refundAddedCost(
        band: Band,
        costId: String,
        me: String?,
        refundsCosts: Boolean,
        today: LocalDate,
        amountText: (Money) -> String,
        costName: (Cost) -> String,
    ) {
        val cost = band.cost(costId) ?: return
        if (CostLedger.outstanding(band, cost).isPositive) add(band, me, refundsCosts, today, amountText, costName, refund = costId)
    }

    /**
     * Saves the open form with [dispatch], one operation at a time (several people's contributions are one entry each,
     * with their own Undo and History line); closes it when all went through. On a refusal the form stays open, with
     * who was saved already no longer ticked (no doubles on a retry), and the refusal is returned.
     */
    fun save(dispatch: (Operation) -> Rejection?): Rejection? {
        val form = form?.takeIf { it.ready } ?: return null
        val refused = form.save(dispatch)
        if (refused == null) this.form = null
        return refused
    }

    fun delete(dispatch: (Operation) -> Rejection?): Rejection? {
        val entry = form?.initial ?: return null
        val refused = dispatch(RemoveFundEntry(entry.id))
        if (refused == null) form = null
        return refused
    }
}

/**
 * Add or edit an entry: money paid in by someone (a member, several at once, or from outside the band), or the refund
 * of a cost; money leaves the fund only for one. [refundsCosts]: leads only, as refunding a cost decides about it
 * (I7 §3.3); the treasurer records money paid in. [refund]: a cost to refund from the start.
 */
@Stable
class FundEntryForm(
    band: Band,
    val initial: FundEntry?,
    me: String?,
    val refundsCosts: Boolean,
    today: LocalDate,
    private val amountText: (Money) -> String,
    val costName: (Cost) -> String,
    refund: String? = null,
) {
    val isNew: Boolean get() = initial == null

    private val refundCost: Cost? = refund?.let { band.cost(it) }

    /** Money in (true) or out: a refund. */
    var incoming by mutableStateOf(initial?.amount?.isPositive ?: (refund == null))

    var amount by mutableStateOf(
        initial?.let { amountText(Money(abs(it.amount.minor))) } ?: refundCost?.let { amountText(CostLedger.outstanding(band, it)) } ?: "",
    )
    var description by mutableStateOf(initial?.description ?: refundCost?.let(costName).orEmpty())
    var date by mutableStateOf(initial?.date ?: today)
    var costId by mutableStateOf(initial?.costId ?: refund)
        private set

    /** The description filled in from the picked cost: replaced when another is picked, unless the user typed over it. */
    private var filled by mutableStateOf(refundCost?.let(costName))

    /** Who paid it in: members, or null for outside the band. The one adding it by default. */
    var payers by mutableStateOf(
        if (initial != null) listOf(initial.paidBy) else listOf(me?.takeIf { id -> people(band).any { it.id == id } }),
    )

    var choosingPeople by mutableStateOf(false)

    val parsed: Money? by derivedStateOf { parseMoney(amount)?.takeIf { it.isPositive } }
    private val signed: Money? get() = parsed?.let { if (incoming) it else -it }
    val ready: Boolean by derivedStateOf { signed != null && description.isNotBlank() && (incoming || costId != null) }

    /** Who can have paid in, as the band is now: members who may hold money, alphabetical. */
    fun people(band: Band): List<Member> = band.members.filter { !it.archived && it.role != Role.VIEWER }.sortedByName { it.name }

    /** Costs with something left to refund now; refunding is only offered for new entries, the link cannot change later. */
    fun pendingCosts(band: Band): List<Cost> = band.costs.filter { CostLedger.outstanding(band, it).isPositive }

    /** The cost to refund: its outstanding amount, and its name unless the user typed a description. */
    fun pickCost(band: Band, id: String?) {
        costId = id
        val cost = id?.let { band.cost(it) } ?: return
        amount = amountText(CostLedger.outstanding(band, cost))
        if (description.isBlank() || description == filled) {
            description = costName(cost)
            filled = description
        }
    }

    /** Who paid: from the people dialog; nobody chosen keeps the previous choice. */
    fun choosePeople(band: Band, chosen: Set<String>) {
        if (chosen.isNotEmpty()) payers = people(band).map { it.id }.filter { it in chosen }
    }

    /** What Save sends: one entry per payer for money in, a refund, or only the changed fields of an existing entry. */
    fun operations(): List<Operation> {
        val signed = signed ?: return emptyList()
        val text = description.trim()
        return when {
            initial == null && incoming -> payers.map { AddFundEntry(newId(), date, text, signed, paidBy = it) }

            initial == null -> listOf(AddFundEntry(newId(), date, text, signed, costId))

            else -> listOf(
                UpdateFundEntry(
                    initial.id,
                    date = Change(date).takeIf { date != initial.date },
                    description = Change(text).takeIf { text != initial.description },
                    amount = Change(signed).takeIf { signed != initial.amount },
                    paidBy = Change(payers.single()).takeIf { incoming && payers.single() != initial.paidBy },
                ),
            )
        }
    }

    /** Runs [operations] in order; stops at the first refusal, dropping the payers saved before it. Only when [ready]. */
    fun save(dispatch: (Operation) -> Rejection?): Rejection? {
        check(ready) { "Save is offered only when the form is ready" }
        val ops = operations()
        for ((index, op) in ops.withIndex()) {
            val refused = dispatch(op) ?: continue
            if (initial == null && incoming) payers = payers.drop(index)
            return refused
        }
        return null
    }
}
