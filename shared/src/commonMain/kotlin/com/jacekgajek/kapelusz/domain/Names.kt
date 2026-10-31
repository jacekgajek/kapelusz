package com.jacekgajek.kapelusz.domain

/**
 * People in alphabetical order (ITERATION_8 §2), and finding them by name (§1.2). Without a platform collator: the
 * same order on the JVM, Android and the web.
 */
object Names {
    /** Polish letters, each sorted right after its plain one (Ł after L, Ź before Ż). */
    private const val POLISH = "ąćęłńóśźż"
    private const val POLISH_BASE = "acelnoszz"

    /** Other accented Latin letters, equal to their plain one in the order and in searches (Müller, José). */
    private const val ACCENTED = "áàâäãåéèêëíìîïòôöõúùûüýÿçñšžčřďťňľĺŕő"
    private const val ACCENTED_BASE = "aaaaaaeeeeiiiioooouuuuyycnszcrdtnllro"

    /**
     * Names ignoring case and spaces around them, Polish letters in their place. Names that differ only in accents
     * other than Polish ones, or in case, keep a fixed order by the exact text.
     */
    val order: Comparator<String> = compareBy<String> { sortKey(it) }.thenBy { it }

    /** [name] matches a search for [query]: any part of it, ignoring case and accents ("lukasz" finds "Łukasz"). */
    fun matches(name: String, query: String): Boolean = fold(name).contains(fold(query.trim()))

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

    private fun fold(text: String): String = buildString {
        for (c in text.lowercase()) {
            val polish = POLISH.indexOf(c)
            append(if (polish >= 0) POLISH_BASE[polish] else plain(c))
        }
    }

    private fun plain(c: Char): Char = ACCENTED.indexOf(c).let { if (it >= 0) ACCENTED_BASE[it] else c }
}

/** [this] in alphabetical order of [name] (ITERATION_8 §2). */
fun <T> Iterable<T>.sortedByName(name: (T) -> String): List<T> = sortedWith { a, b -> Names.order.compare(name(a), name(b)) }
