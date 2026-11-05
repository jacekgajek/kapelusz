package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.ui.components.AmountEntry
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.view.HandOver

/**
 * Money handed over by hand in one event: from whom, to whom, how much, with a note. [handOver] is who may give what
 * to whom as the band was when the form opened; [me] is the sender first when among them. Only when [handOver] has a
 * sender with a recipient (see [HandOverForm.open]).
 */
@Stable
class HandOverForm private constructor(
    private val eventId: Id,
    private val handOver: HandOver,
    firstFrom: Id,
    firstTo: Id,
    private val amountText: (Money) -> String,
) {
    var from: Id by mutableStateOf(firstFrom)
        private set
    var to: Id by mutableStateOf(firstTo)
    val amount = AmountEntry(handOver.amount(firstFrom).takeIf { it.isPositive }, null, amountText)
    var note by mutableStateOf("")

    val senders: List<Id> get() = handOver.senders

    /** Whom [from] may hand over to. */
    val recipients: List<Id> get() = handOver.recipients(from)

    /** At most what the sender holds (an artist: owes back): a hand-over passes on money, it doesn't make any up. */
    val held: Money get() = handOver.amount(from)

    val ready: Boolean by derivedStateOf { amount.gross?.let { it <= held } == true && from != to }

    /** Another sender: a recipient they may not give to is replaced, and what they hold is offered as the amount. */
    fun sender(id: Id) {
        from = id
        if (to !in recipients) to = recipients.firstOrNull() ?: to
        held.takeIf { it.isPositive }?.let { amount.typeGross(amountText(it)) }
    }

    /** What Save sends; only when [ready]. */
    fun operation(): RecordTransfer {
        val gross = amount.gross
        check(gross != null && ready) { "Save is offered only when the form is ready" }
        return RecordTransfer(eventId, newId(), from, to, gross, note.ifBlank { null })
    }

    companion object {
        /** The form, or null when there is nobody to hand over from or to (e.g. a sync removed the user from the event). */
        fun open(eventId: Id, handOver: HandOver, me: Id?, amountText: (Money) -> String): HandOverForm? {
            val from = me?.takeIf { it in handOver.senders } ?: handOver.senders.firstOrNull() ?: return null
            val to = handOver.recipients(from).firstOrNull() ?: return null
            return HandOverForm(eventId, handOver, from, to, amountText)
        }
    }
}
