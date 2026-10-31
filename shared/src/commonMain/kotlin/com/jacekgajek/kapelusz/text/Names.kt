package com.jacekgajek.kapelusz.text

/**
 * People in alphabetical order (I8 §2), and finding them by name (§1.2). Without a platform collator: the
 * same order on the JVM, Android and the web.
 */
object Names {
    /** Polish letters, each sorted right after its plain one (Ł after L, Ź before Ż). */
    private const val POLISH = "ąćęłńóśźż"
    private const val POLISH_BASE = "acelnoszz"

    /**
     * Names ignoring case and spaces around them, Polish letters in their place. Names that differ only in accents
     * other than Polish ones, or in case, keep a fixed order by the exact text.
     */
    val order: Comparator<String> = compareBy<String> { sortKey(it) }.thenBy { it }

    /** [name] matches a search for [query]: any part of it, ignoring case and accents ("lukasz" finds "Łukasz"). */
    fun matches(name: String, query: String): Boolean = fold(name).contains(fold(query.trim()))

    /**
     * Lower case without diacritics, for searching and ordering: a table for Polish and common Latin letters, as Kotlin's
     * common library has no Unicode normalisation; combining marks (text pasted in decomposed form, e.g. from a Mac) are
     * dropped, and any whitespace (a tab, a non-breaking space) is a space.
     */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text.lowercase()) {
            if (c in '\u0300'..'\u036F') continue
            if (c.isWhitespace()) {
                append(' ')
                continue
            }
            append(plain(c))
        }
    }

    private fun sortKey(name: String): String = buildString {
        for (c in name.trim().lowercase()) {
            val polish = POLISH.indexOf(c)
            when {
                // The plain letter, then a mark above every other letter, so "Lz" comes before "Ła"; ź and ż apart.
                polish >= 0 -> append(POLISH_BASE[polish]).append((0xF000 + polish).toChar())

                else -> append(plain(c))
            }
        }
    }

    /** The plain letters a letter stands for; Polish letters and the rest of the table alike. */
    private fun plain(c: Char): String = FOLDS[c] ?: c.toString()

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
        fold("řŕ", "r")
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
}

/** [this] in alphabetical order of [name] (I8 §2). */
fun <T> Iterable<T>.sortedByName(name: (T) -> String): List<T> = sortedWith { a, b -> Names.order.compare(name(a), name(b)) }
