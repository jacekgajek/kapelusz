package com.jacekgajek.kapelusz.view

import com.jacekgajek.kapelusz.domain.Names
import com.jacekgajek.kapelusz.model.Member

/**
 * Searching the event list (ITERATION_6 §6): each typed word somewhere in an event's name or Location, in any order,
 * ignoring case and diacritics ("lodz" finds "Łódź").
 */
class EventSearch(summaries: List<EventSummary>) {
    private val entries = summaries.map { it to Names.fold(listOfNotNull(it.event.name, it.event.location).joinToString("\n")) }

    /** The summaries matching every word of [words] (from [searchWords]), in their order. */
    fun find(words: List<String>): List<EventSummary> = entries.filter { (_, text) -> words.all { it in text } }.map { it.first }
}

/**
 * What a search looks through: every event the user may see, whatever the list's filters (an old gig is often
 * archived, or one they didn't play); an artist only their own. Settles each event, the costly part, and [pause]s between them.
 */
suspend fun searchableSummaries(view: BandView, me: Member?, artist: Boolean, pause: suspend () -> Unit = {}): List<EventSummary> {
    val summaries = ArrayList<EventSummary>(view.band.events.size)
    for (event in view.band.events) {
        pause()
        val settlement = view.settlement(event)
        val summary = EventSummary(event, settlement, myNumber(event, settlement, me))
        if (!artist || summary.isMine) summaries += summary
    }
    return summaries.sortedWith(EVENT_ORDER)
}

/** The query's words, folded as the searched text; none for a blank query. */
fun searchWords(query: String): List<String> = Names.fold(query).split(' ').filter { it.isNotEmpty() }
