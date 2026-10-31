package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.EventTab
import com.jacekgajek.kapelusz.app.data.SharedAttached
import com.jacekgajek.kapelusz.app.data.SharedHandover
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.rules.isArtist
import com.jacekgajek.kapelusz.settlement.Settlement
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the event screen does with files shared to the app for this event (ITERATION_6 §1). */
enum class HandoverAction {
    /** Opens the cost form for the event, which takes the files. */
    ADD_COST,

    /** The event is gone meanwhile (or hidden from an artist): the files must not turn up there later. */
    DROP,
}

/**
 * One event's screen (ITERATION_5 §4.4): the event and its settlement as the band is now, the pages the user gets,
 * the dialog open, and the files shared to the app on their way in. Scoped to the screen's back-stack entry; the
 * pager and the lists' scroll positions stay in the screen, as they are its own.
 */
class EventViewModel(val eventId: String, bands: StateFlow<BandState>) : ViewModel() {
    private var ready by mutableStateOf(bands.value as? BandState.Ready)

    init {
        viewModelScope.launch { bands.collect { ready = it as? BandState.Ready } }
    }

    /** Artists see only their own events and pages (ITERATION_4 §1.1). */
    val artist: Boolean by derivedStateOf { ready?.let { it.band.isArtist(it.userId) } ?: false }

    /** The event, unless gone or hidden from an artist not playing it (archived ones too). */
    val event: Event? by derivedStateOf {
        val state = ready ?: return@derivedStateOf null
        state.band.event(eventId)?.takeUnless { artist && state.me?.let { me -> it.participant(me.id) } == null }
    }

    val settlement: Settlement? by derivedStateOf { event?.let { ready?.view?.settlement(it) } }

    /** The gig, its money, and who gets what; artists get the gig and their own money. */
    val tabs: List<EventTab> by derivedStateOf { if (artist) listOf(EventTab.GIG, EventTab.MONEY) else EventTab.entries }

    /** The When and where editor. */
    var editing by mutableStateOf(false)

    /** Files shared to this event that were attached: shown once in a snackbar with Undo, then forgotten. */
    var attached by mutableStateOf<SharedAttached?>(null)
        private set

    /** What to do with [handover], files shared to the app, while this screen is in front; null: nothing. */
    fun handoverAction(handover: SharedHandover?): HandoverAction? = when {
        handover?.eventId != eventId -> null
        event == null -> HandoverAction.DROP
        handover.toCost -> HandoverAction.ADD_COST
        else -> null
    }

    /** Takes [shared] over from the app's inbox (its own clearing must not cancel the snackbar showing here). */
    fun takeAttached(shared: SharedAttached?, bandId: String): Boolean {
        if (shared == null || shared.eventId != eventId || shared.bandId != bandId) return false
        attached = shared
        return true
    }

    fun attachedShown() {
        attached = null
    }
}
