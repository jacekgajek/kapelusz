package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.ui.components.AmountEntry
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ReceiveAwaited

/**
 * "Received…" on an awaited fee: who got it, and how much of it (all, by default). [parties]: who may have
 * received it, as allowed when the form opened; [me] is picked first when among them.
 */
@Stable
class ReceiveForm(
    private val eventId: String,
    val awaited: Holding,
    val parties: List<String>,
    me: String?,
    amountText: (Money) -> String,
) {
    var partyId by mutableStateOf(me?.takeIf { it in parties } ?: parties.first())

    val amount = AmountEntry(awaited.amount, awaited.vat, amountText)

    /** No more than was awaited can come in. */
    val ready: Boolean by derivedStateOf { amount.gross?.let { it <= awaited.amount } ?: false }

    /** Records with [run] (true when it went through) that [partyId] got the amount, as a new entry of theirs. Only when [ready]. */
    fun save(run: (Operation) -> Boolean): Boolean {
        check(ready) { "Save is offered only when the form is ready" }
        return run(ReceiveAwaited(eventId, awaited.id, newId(), partyId, amount.gross!!))
    }
}
