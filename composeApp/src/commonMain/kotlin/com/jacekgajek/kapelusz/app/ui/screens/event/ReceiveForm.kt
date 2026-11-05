package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ReceiveAwaited

/**
 * "Received…" on an awaited fee: who got it. The fee comes whole, as [awaited] has it. [parties]: who may have
 * received it, as allowed when the form opened; [me] is picked first when among them.
 */
@Stable
class ReceiveForm(
    private val eventId: String,
    val awaited: Holding,
    val parties: List<String>,
    me: String?,
) {
    var partyId by mutableStateOf(me?.takeIf { it in parties } ?: parties.first())

    /** Records with [run] (true when it went through) that [partyId] got the fee, as a new entry of theirs. */
    fun save(run: (Operation) -> Boolean): Boolean = run(ReceiveAwaited(eventId, awaited.id, newId(), partyId, awaited.amount))
}
