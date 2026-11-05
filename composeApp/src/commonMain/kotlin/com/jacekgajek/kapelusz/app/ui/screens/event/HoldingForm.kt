package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.ui.components.AmountEntry
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.UpdateHolding

/**
 * Collected money, added or edited: who holds it, how much, from where. An awaited fee is held by the [ORGANISER] and nobody
 * else can be picked for it. [initial]: the entry as it was when the form opened (none for a new one), so that
 * Save sends only what the user changed. [parties]: who may be picked as the holder, as allowed when the form opened.
 */
@Stable
class HoldingForm(
    private val eventId: String,
    val initial: Holding?,
    defaultParty: String,
    val parties: List<String>,
    amountText: (Money) -> String,
    initialDrafts: List<PreparedFile> = emptyList(),
) {
    val isNew: Boolean get() = initial == null

    var partyId by mutableStateOf(defaultParty)

    val amount = AmountEntry(initial?.amount, amountText)
    var source by mutableStateOf(initial?.source ?: if (defaultParty == ORGANISER) IncomeSource.CONTRACT else IncomeSource.HAT)
    var note by mutableStateOf(initial?.note.orEmpty())

    /** Files picked before a new entry is saved; attached right after it. */
    var drafts by mutableStateOf(initialDrafts)

    /** Deleting takes its files with it, for good: asked first when there are any. */
    var confirmingDelete by mutableStateOf(false)

    /** The holder is chosen only for a new entry by a participant: the awaited fee is the organiser's. */
    val choosesParty: Boolean get() = isNew && partyId != ORGANISER

    val ready: Boolean by derivedStateOf { amount.gross != null }

    /**
     * Saves with [run] (true when it went through): a new entry, then its [drafts] with [attach]; or only the
     * changed fields of an existing one. Only when [ready].
     */
    fun save(run: (Operation) -> Boolean, attach: (holdingId: String, List<PreparedFile>) -> Unit): Boolean {
        val gross = amount.gross
        check(gross != null) { "Save is offered only when the form is ready" }
        val initial = initial
        return if (initial == null) {
            val id = newId()
            val ok = run(AddHolding(eventId, id, partyId, gross, source, note.ifBlank { null }))
            if (ok) attach(id, drafts)
            ok
        } else {
            run(
                UpdateHolding(
                    eventId,
                    initial.id,
                    amount = Change(gross).takeIf { gross != initial.amount },
                    source = Change(source).takeIf { source != initial.source },
                    note = Change(note.ifBlank { null }).takeIf { note.trim() != initial.note.orEmpty() },
                ),
            )
        }
    }

    /** Removes the existing entry with [run]; false for a new one, which has nothing to remove. */
    fun delete(run: (Operation) -> Boolean): Boolean {
        confirmingDelete = false
        return initial != null && run(RemoveHolding(eventId, initial.id))
    }
}
