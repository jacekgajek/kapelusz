package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.intents.Carry
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.settings.EventTab
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.rules.isArtist
import com.jacekgajek.kapelusz.settlement.Settlement
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One event's screen: the event and its settlement as the band is now, the pages the user gets,
 * the dialog open, and what the screen was opened with ([carry]: files shared to the app). Scoped to
 * the screen's back-stack entry; the pager and the lists' scroll positions stay in the screen, as they are its own.
 */
class EventViewModel(val eventId: String, bands: StateFlow<BandState>, carry: Carry? = null) : ViewModel() {
    private var ready by mutableStateOf(bands.value as? BandState.Ready)

    init {
        viewModelScope.launch { bands.collect { ready = it as? BandState.Ready } }
    }

    /** Artists see only their own events and pages. */
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
    var attached by mutableStateOf(carry as? Carry.Attached)
        private set

    private var collectedFiles = (carry as? Carry.NewCollected)?.files

    fun attachedShown() {
        attached = null
    }

    /** Files shared to the app for new received money of this event, once (the entry's dialog opens with them). */
    fun takeCollectedFiles(): List<PreparedFile>? = collectedFiles.also { collectedFiles = null }
}
