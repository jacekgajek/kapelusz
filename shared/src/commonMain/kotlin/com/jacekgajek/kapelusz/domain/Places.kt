package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.Event

/** A place as shared from Google Maps: its name (if given) and its link. */
data class SharedPlace(val name: String?, val url: String)

/** Google Maps links (ITERATION_2 §4.4): reading what Maps shares, and the links Kapelusz opens. */
object Places {
    /** The longest link an event stores; longer full place links are rejected rather than cut. */
    const val MAX_URL = 1000

    private val candidate = Regex("""https://\S+""")

    // Exact hosts only, so "google.com.evil.io" or "maps.app.goo.gl@evil.io" are not taken for Maps.
    private val mapHost = Regex("""maps\.app\.goo\.gl|goo\.gl|(www\.|maps\.)?google\.(com|[a-z]{2}|com?\.[a-z]{2})""")

    /** A link an event may store: https, URI characters only (printable ASCII), at most [MAX_URL]. */
    fun isValidUrl(url: String): Boolean =
        url.startsWith("https://") && url.length <= MAX_URL && url.all { it in '!'..'~' && it != '"' && it != '<' && it != '>' && it != '\\' }

    private fun isMapLink(url: String): Boolean {
        val rest = url.removePrefix("https://")
        val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val path = rest.drop(host.length)
        if (!mapHost.matches(host)) return false
        return when {
            host == "maps.app.goo.gl" -> true
            host == "goo.gl" -> path.startsWith("/maps")
            host.startsWith("maps.") -> true
            else -> path.startsWith("/maps")
        }
    }

    /**
     * The place in a text shared or copied from Google Maps, e.g. "Klub Stodoła\nhttps://maps.app.goo.gl/abc", or
     * null without a Maps link. The name is whatever text surrounds the link (first line).
     */
    fun parse(text: String): SharedPlace? {
        val match = candidate.findAll(text).firstOrNull { isMapLink(it.value.trimEnd('.', ',', ')')) } ?: return null
        val url = match.value.trimEnd('.', ',', ')')
        if (!isValidUrl(url)) return null
        val name = text.removeRange(match.range).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return SharedPlace(name, url)
    }

    /** Google Maps searching for [query], to find the place and share it back (Select on map). */
    fun searchUrl(query: String?): String =
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://www.google.com/maps/search/?api=1&query=${encode(it)}" } ?: "https://www.google.com/maps"

    /** Where Navigate goes: the stored Maps link (the exact pin), otherwise directions to the location text. */
    fun navigateUrl(event: Event): String? =
        event.mapUrl ?: event.location?.takeIf { it.isNotBlank() }?.let { "https://www.google.com/maps/dir/?api=1&destination=${encode(it)}" }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in "-_.~")) append(c) else append("%" + (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
