package com.jacekgajek.artsplit.domain

import com.jacekgajek.artsplit.model.Event

/** A place as shared from Google Maps: its name (if given) and its link. */
data class SharedPlace(val name: String?, val url: String)

/** Google Maps links (VERSION_2 §4.4): reading what Maps shares, and the links Art Split opens. */
object Places {
    private val mapLink = Regex("""https://(maps\.app\.goo\.gl|goo\.gl/maps|(www\.)?google\.[a-z.]+/maps|maps\.google\.[a-z.]+)\S*""")

    /**
     * The place in a text shared or copied from Google Maps, e.g. "Klub Stodoła\nhttps://maps.app.goo.gl/abc", or
     * null without a Maps link. The name is whatever text surrounds the link (first line).
     */
    fun parse(text: String): SharedPlace? {
        val match = mapLink.find(text) ?: return null
        val name = text.removeRange(match.range).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return SharedPlace(name, match.value.trimEnd('.', ',', ')'))
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
