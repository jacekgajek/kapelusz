package com.jacekgajek.kapelusz.app.ui.model

/**
 * Searching the event list (ITERATION_6 §6): each typed word somewhere in an event's name or Location, in any order,
 * ignoring case and diacritics ("lodz" finds "Łódź").
 */
class EventSearch(summaries: List<EventSummary>) {
    private val entries = summaries.map { it to foldForSearch(listOfNotNull(it.event.name, it.event.location).joinToString("\n")) }

    /** The summaries matching every word of [words] (from [searchWords]), in their order. */
    fun find(words: List<String>): List<EventSummary> =
        entries.filter { (_, text) -> words.all { it in text } }.map { it.first }
}

/** The query's words, folded as the searched text; none for a blank query. */
fun searchWords(query: String): List<String> = foldForSearch(query).split(' ', '\t', '\n').filter { it.isNotEmpty() }

/**
 * Lower case without diacritics: a table for Polish and common Latin letters, as Kotlin's common library has no
 * Unicode normalisation.
 */
fun foldForSearch(text: String): String = buildString(text.length) {
    for (c in text.lowercase()) {
        val folded = FOLDS[c]
        if (folded == null) append(c) else append(folded)
    }
}

private val FOLDS: Map<Char, String> = buildMap {
    fun fold(chars: String, to: String) = chars.forEach { put(it, to) }
    fold("ąáàâäãåā", "a")
    fold("ćçč", "c")
    fold("ď", "d")
    fold("ęéèêëěē", "e")
    fold("íìîïī", "i")
    fold("łľĺ", "l")
    fold("ńňñ", "n")
    fold("óòôöõøō", "o")
    fold("ř", "r")
    fold("śšş", "s")
    fold("ť", "t")
    fold("úùûüůū", "u")
    fold("ýÿ", "y")
    fold("źżž", "z")
    put('ß', "ss")
}
