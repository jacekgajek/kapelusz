package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.model.Holding
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER

/** What is open over an event's collected money: one dialog at a time, with its form. Owned by the event's screen model. */
@Stable
class CollectedDialogs(private val eventId: String) {
    var open by mutableStateOf<CollectedDialog?>(null)
        private set

    /** A new entry held by [defaultParty] (one of [parties]), with [drafts]: files shared to the app for it. */
    fun addEntry(defaultParty: String, parties: List<String>, amountText: (Money) -> String, drafts: List<PreparedFile> = emptyList()) {
        open = CollectedDialog.Entry(HoldingForm(eventId, initial = null, defaultParty, parties, amountText, drafts))
    }

    /** A fee the organiser still owes. */
    fun addAwaited(amountText: (Money) -> String) = addEntry(ORGANISER, listOf(ORGANISER), amountText)

    fun editEntry(holding: Holding, parties: List<String>, amountText: (Money) -> String) {
        open = CollectedDialog.Entry(HoldingForm(eventId, holding, holding.partyId, parties, amountText))
    }

    /** An awaited fee came in: who got it, from [parties] ([me] first), and how much of it. */
    fun receive(awaited: Holding, parties: List<String>, me: String?, amountText: (Money) -> String) {
        open = CollectedDialog.Receive(ReceiveForm(eventId, awaited, parties, me, amountText))
    }

    /** An awaited fee that will never come. */
    fun welch(awaited: Holding) {
        open = CollectedDialog.Welching(awaited)
    }

    fun dismiss() {
        open = null
    }
}

/** What is open over the collected money: an entry's form, an awaited fee being received, or one being given up. */
sealed interface CollectedDialog {
    data class Entry(val form: HoldingForm) : CollectedDialog

    data class Receive(val form: ReceiveForm) : CollectedDialog

    /** An awaited fee that will never come: asked first, as its files (the contract) go with it. */
    data class Welching(val holding: Holding) : CollectedDialog
}
