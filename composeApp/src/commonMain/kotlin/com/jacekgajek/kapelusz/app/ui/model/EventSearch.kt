package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import kotlinx.coroutines.yield

/**
 * Searching the event list (ITERATION_6 §6): each typed word somewhere in an event's name or Location, in any order,
 * ignoring case and diacritics ("lodz" finds "Łódź").
 */
class EventSearch(summaries: List<EventSummary>) {
    private val entries = summaries.map { it to foldForSearch(listOfNotNull(it.event.name, it.event.location).joinToString("\n")) }

    /** The summaries matching every word of [words] (from [searchWords]), in their order. */
    fun find(words: List<String>): List<EventSummary> = entries.filter { (_, text) -> words.all { it in text } }.map { it.first }
}

/**
 * What a search looks through: every event the user may see, whatever the list's filters (an old gig is often
 * archived, or one they didn't play); an artist only their own. Settles each event, the costly part: yields between
 * events, to be cancelled when the band changes again and (on the web's one thread) to let the screen draw.
 */
suspend fun searchableSummaries(band: Band, me: Member?, artist: Boolean): List<EventSummary> {
    val summaries = ArrayList<EventSummary>(band.events.size)
    for (event in band.events) {
        yield()
        val settlement = SettlementCalculator.settle(band, event)
        val summary = EventSummary(event, settlement, myNumber(event, settlement, me))
        if (!artist || summary.isMine) summaries += summary
    }
    return summaries.sortedWith(EVENT_ORDER)
}

/** The query's words, folded as the searched text; none for a blank query. */
fun searchWords(query: String): List<String> = foldForSearch(query).split(' ').filter { it.isNotEmpty() }

/**
 * Lower case without diacritics: a table for Polish and common Latin letters, as Kotlin's common library has no
 * Unicode normalisation; combining marks (text pasted in decomposed form, e.g. from a Mac) are dropped, and any
 * whitespace (a tab, a non-breaking space) is a space.
 */
fun foldForSearch(text: String): String = buildString(text.length) {
    for (c in text.lowercase()) {
        if (c in '\u0300'..'\u036F') continue
        if (c.isWhitespace()) {
            append(' ')
            continue
        }
        val folded = FOLDS[c]
        if (folded == null) append(c) else append(folded)
    }
}

private val FOLDS: Map<Char, String> = buildMap {
    fun fold(chars: String, to: String) = chars.forEach { put(it, to) }
    fold("ąáàâäãåā", "a")
    fold("ćçč", "c")
    fold("ďđ", "d")
    fold("ęéèêëěē", "e")
    fold("íìîïīı", "i")
    fold("łľĺ", "l")
    fold("ńňñ", "n")
    fold("óòôöõøōő", "o")
    fold("ř", "r")
    fold("śšşș", "s")
    fold("ťţț", "t")
    fold("úùûüůūű", "u")
    fold("ýÿ", "y")
    fold("ğ", "g")
    fold("źżž", "z")
    put('ß', "ss")
    put('æ', "ae")
    put('œ', "oe")
}
