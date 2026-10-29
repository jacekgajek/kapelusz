package com.jacekgajek.kapelusz.domain

/**
 * A bank account number (ITERATION_7 §2i): an IBAN, or a Polish NRB (its 26 digits, without `PL`), checked with the
 * IBAN checksum so a mistyped digit is caught. A Polish one is stored and copied as the 26 digits, as Polish bank apps
 * take it, and shown `61 1090 1014 0000 0712 1981 2874`; any other with its country code, in fours.
 */
object BankAccount {
    private const val NRB_DIGITS = 26
    private val IBAN = Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")

    /** [input] as stored: spaces, dashes and invisible marks dropped, letters capitalised; null if not an account number. */
    fun normalize(input: String): String? {
        val compact = input.filterNot {
            it.isWhitespace() || it == ' ' || it == ' ' ||
                it.category == CharCategory.DASH_PUNCTUATION || it.category == CharCategory.FORMAT
        }.uppercase()
        val iban = if (compact.length == NRB_DIGITS && compact.all { it in '0'..'9' }) "PL$compact" else compact
        if (!IBAN.matches(iban) || !checksumValid(iban)) return null
        return if (iban.startsWith("PL") && iban.length == NRB_DIGITS + 2) iban.drop(2) else iban
    }

    /** The IBAN check (ISO 13616): the first four characters moved to the end, letters as 10 to 35, mod 97 is 1. */
    private fun checksumValid(iban: String): Boolean {
        var remainder = 0
        for (c in iban.drop(4) + iban.take(4)) {
            remainder = if (c in '0'..'9') (remainder * 10 + (c - '0')) % 97 else (remainder * 100 + (c - 'A' + 10)) % 97
        }
        return remainder == 1
    }

    private fun polish(stored: String) = stored.length == NRB_DIGITS && stored.all { it in '0'..'9' }

    /** For reading: a Polish number as its two check digits and then fours, any other in fours. */
    fun display(stored: String): String =
        if (polish(stored)) (listOf(stored.take(2)) + stored.drop(2).chunked(4)).joinToString(" ") else stored.chunked(4).joinToString(" ")

    /** For pasting into a bank app: as stored, without spaces. */
    fun copyText(stored: String): String = stored
}
