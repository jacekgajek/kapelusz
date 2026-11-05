package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Transfer
import com.jacekgajek.kapelusz.view.HandOver

/** What is open over an event's transfers: one dialog at a time, with its form. Owned by the event's screen model. */
@Stable
class TransfersDialogs(private val eventId: Id) {
    var open by mutableStateOf<TransfersDialog?>(null)
        private set

    /** Handing money over by hand; nothing opens when [handOver] has nobody to give or receive. */
    fun handOver(handOver: HandOver, me: Id?, amountText: (Money) -> String) {
        open = HandOverForm.open(eventId, handOver, me, amountText)?.let { TransfersDialog.HandingOver(it) }
    }

    fun remove(transfer: Transfer) {
        open = TransfersDialog.Removing(transfer)
    }

    fun dismiss() {
        open = null
    }
}

/** What is open over the transfers: a hand-over's form, or a transfer being removed. */
sealed interface TransfersDialog {
    data class HandingOver(val form: HandOverForm) : TransfersDialog

    data class Removing(val transfer: Transfer) : TransfersDialog
}
