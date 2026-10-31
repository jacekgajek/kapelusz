package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.rules.isArtist
import com.jacekgajek.kapelusz.view.EventSearch
import com.jacekgajek.kapelusz.view.EventSummary
import com.jacekgajek.kapelusz.view.searchWords
import com.jacekgajek.kapelusz.view.searchableSummaries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * The events tab's state that outlives a recomposition: the search and the new event dialog. The
 * list itself is a query of the band ([eventSummaries]) and stays in the screen. Scoped to the tab's back-stack entry.
 */
class EventsViewModel(bands: StateFlow<BandState>, private val background: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private var ready by mutableStateOf(bands.value as? BandState.Ready)

    var creating by mutableStateOf(false)

    var searching by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    /** The keyboard only when the search icon is tapped, not each time the row comes back (scrolling, coming back). */
    var focusSearch by mutableStateOf(false)
        private set

    /**
     * All events the user may see, whatever the filters, prepared in the background once the search opens; kept until
     * the band changes, then built again (the old results shown meanwhile). Null while not searching or not ready yet.
     */
    var search by mutableStateOf<EventSearch?>(null)
        private set

    private var preparing: Job? = null

    init {
        viewModelScope.launch {
            bands.collect { state ->
                val before = ready?.band
                ready = state as? BandState.Ready
                // The band changed (not merely its state object, which every sync renews): built again, the old results
                // shown meanwhile.
                if (searching && ready?.band != before) prepare()
            }
        }
    }

    private fun prepare() {
        preparing?.cancel()
        preparing = viewModelScope.launch {
            val state = ready ?: return@launch
            val artist = state.band.isArtist(state.userId)
            search = withContext(background) { EventSearch(searchableSummaries(state.view, state.me, artist) { yield() }) }
        }
    }

    val words: List<String> by derivedStateOf { searchWords(query) }

    /** The search being prepared behind a query already typed. */
    val building: Boolean get() = search == null && words.isNotEmpty()

    /**
     * What the list shows: until something is typed, [summaries] as filtered; else the search's results, leaving out
     * events deleted meanwhile (a search being built again); null while it is still being built.
     */
    fun results(summaries: List<EventSummary>): List<EventSummary>? {
        if (!searching || words.isEmpty()) return summaries
        val band = ready?.band ?: return emptyList()
        return search?.find(words)?.filter { band.event(it.event.id) != null }
    }

    fun openSearch() {
        searching = true
        focusSearch = true
        prepare()
    }

    fun searchFocused() {
        focusSearch = false
    }

    fun closeSearch() {
        searching = false
        query = ""
        preparing?.cancel()
        search = null
    }
}
