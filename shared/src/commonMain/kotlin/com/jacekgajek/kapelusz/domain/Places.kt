package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.model.Event

/** A place as copied from Google Maps: its name (if given) and its link. */
data class SharedPlace(val name: String?, val url: String)

/**
 * What a Google Maps link points at ([Places.target]): a place's [name] (with its [pin] when the link has one), a
 * dropped [pin] alone, or a searched [query] text. Coordinates as written in the link (latitude, longitude).
 */
data class MapTarget(val name: String?, val pin: Pair<String, String>?, val query: String?)

/** Google Maps links (ITERATION_2 §4.4): reading a link copied in Maps, and the links Kapelusz opens. */
object Places {
    /** The longest Maps link read from a shared or pasted text; longer ones are not taken for links. */
    const val MAX_URL = 1000

    private val candidate = Regex("""https://\S+""")

    // Exact hosts only, so "google.com.evil.io" or "maps.app.goo.gl@evil.io" are not taken for Maps.
    private val mapHost = Regex("""maps\.app\.goo\.gl|goo\.gl|(www\.|maps\.)?google\.(com|[a-z]{2}|com?\.[a-z]{2})""")

    /** A link an event may store: https, URI characters only (printable ASCII), at most [MAX_URL]. */
    fun isValidUrl(url: String): Boolean =
        url.startsWith("https://") && url.length <= MAX_URL && url.all { it in '!'..'~' && it != '"' && it != '<' && it != '>' && it != '\\' }

    fun isMapLink(url: String): Boolean {
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
     * The place in a text copied from Google Maps (Share › Copy link), e.g. "Klub Stodoła\nhttps://maps.app.goo.gl/abc", or
     * null without a Maps link. The name is whatever text surrounds the link (first line).
     */
    fun parse(text: String): SharedPlace? {
        val match = candidate.findAll(text).firstOrNull { isMapLink(it.value.trimEnd('.', ',', ')')) } ?: return null
        val url = match.value.trimEnd('.', ',', ')')
        if (!isValidUrl(url)) return null
        val name = text.removeRange(match.range).lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return SharedPlace(name, url)
    }

    private val stateCode = Regex("[A-Z]{2,3}")

    /** Fewest characters typed before places are suggested, and the most sent. */
    const val MIN_QUERY = 3
    const val MAX_QUERY = 100

    /**
     * What a picked suggestion puts in Location (ITERATION_5 §9): the name and its town, which is the part of the
     * address before the country ("Stodoła, Warszawa"), or before a state code ("Nashville, TN, USA"), without postal
     * codes; just the name when there is no such part.
     */
    fun label(place: PlaceSuggestion): String {
        val parts = place.address?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().dropLast(1)
            .let { beforeCountry -> if (beforeCountry.size >= 2 && stateCode.matches(beforeCountry.last())) beforeCountry.dropLast(1) else beforeCountry }
        val town = parts.lastOrNull()
            ?.split(" ")?.filter { word -> word.none { it.isDigit() } }?.joinToString(" ")?.takeIf { it.isNotBlank() }
        return if (town == null || place.name.contains(town)) place.name else "${place.name}, $town"
    }

    /**
     * Location for a place with its full [address] (ITERATION_5 §9.1): "Ołówek, Legnicka 36, 53-673 Wrocław", without
     * the [country] at its end, and without the name twice when the address starts with it (a street address picked).
     */
    fun placeText(name: String, address: String?, country: String?): String {
        val short = address?.trim()?.let { full -> country?.let { full.removeSuffix(it).trimEnd(',', ' ') } ?: full }?.takeIf { it.isNotBlank() }
            ?: return name
        return if (short.startsWith(name)) short else "$name, $short"
    }

    /**
     * A pin as Location shows it, also Navigate's destination as it is: 6 decimals (about 10 cm), "51.109120, 17.032570";
     * with a [name] (a place tapped on the map) before it.
     */
    fun pinText(latitude: Double, longitude: Double, name: String? = null): String {
        fun format(value: Double): String {
            val micro = kotlin.math.round(value * 1_000_000).toLong()
            val whole = kotlin.math.abs(micro) / 1_000_000
            val fraction = (kotlin.math.abs(micro) % 1_000_000).toString().padStart(6, '0')
            return "${if (micro < 0) "-" else ""}$whole.$fraction"
        }
        val pin = "${format(latitude)}, ${format(longitude)}"
        // One line, as Location is (a name on the map can have several).
        return name?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it, $pin" } ?: pin
    }

    private val trailingPin = Regex("""(?<![\d.\-])(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$""")

    /** The pin a location ends with (as [pinText] writes it), e.g. to open the map there; null if it ends otherwise. */
    fun pinIn(location: String?): Pair<Double, Double>? {
        val match = trailingPin.find(location ?: return null) ?: return null
        val latitude = match.groupValues[1].toDouble()
        val longitude = match.groupValues[2].toDouble()
        return (latitude to longitude).takeIf { latitude in -90.0..90.0 && longitude in -180.0..180.0 }
    }

    /** Where Navigate goes: directions to the location text, an address or a pin's coordinates. */
    fun navigateUrl(event: Event): String? =
        event.location?.takeIf { it.isNotBlank() }?.let { "https://www.google.com/maps/dir/?api=1&destination=${encode(it)}" }

    private val decimalPin = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val dataPin = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val viewport = Regex("""^@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")

    /**
     * What a full Google Maps link (after its short link's redirects) points at (ITERATION_5 §9.1): a place's name with
     * its exact pin (`!3d…!4d…`, else the map's centre `@…`), a dropped pin (its coordinates as the name, in degrees
     * or decimal), or a searched text (`?q=`); null for anything else.
     */
    fun target(url: String): MapTarget? {
        if (!isMapLink(url)) return null
        val withoutFragment = url.substringBefore('#')
        val path = withoutFragment.removePrefix("https://").substringAfter('/', "").substringBefore('?')
        val params = withoutFragment.substringAfter('?', "").split('&').mapNotNull { pair ->
            pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { decode(it[0]) to decode(it[1]) }
        }.toMap()
        val segments = path.split('/').map(::decode)
        val pin = dataPin.findAll(path).lastOrNull()?.let { it.groupValues[1] to it.groupValues[2] }
            ?: segments.firstNotNullOfOrNull { viewport.find(it) }?.let { it.groupValues[1] to it.groupValues[2] }
        fun after(kind: String) = segments.indexOf(kind).takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }?.takeIf { it.isNotBlank() && !it.startsWith("@") }
        val named = after("place")
        // A search is a text, not a place: the map's centre with it is only where the map was looking.
        val searchedInPath = after("search")
            // Directions: the destination is the last stop before the map's centre and data.
            ?: segments.indexOf("dir").takeIf { it >= 0 }?.let { dir -> segments.drop(dir + 1).takeWhile { !it.startsWith("@") && !it.startsWith("data=") }.lastOrNull { it.isNotBlank() } }
        val searched = searchedInPath ?: listOf("q", "query", "destination", "daddr", "ll").firstNotNullOfOrNull { params[it]?.takeIf { value -> value.isNotBlank() } }
        val text = named ?: searched
        decimalPin.find(text.orEmpty())?.let { return MapTarget(null, it.groupValues[1] to it.groupValues[2], null) }
        // A dropped pin is named by its coordinates in degrees (52°13'47.0"N 21°00'43.9"E): the pin, without a name.
        if (text != null && '°' in text) return pin?.let { MapTarget(null, it, null) }
        return when {
            named != null -> MapTarget(named, pin, null)
            searched != null -> MapTarget(null, null, searched)
            pin != null -> MapTarget(null, pin, null)
            else -> null
        }
    }

    /** `%xx` and `+` decoded (UTF-8); a malformed escape is kept as it is. */
    private fun decode(value: String): String {
        val bytes = mutableListOf<Byte>()
        val plain = StringBuilder()
        // Plain text is encoded in runs, so a character outside the BMP (a surrogate pair) stays whole.
        fun flush() {
            bytes += plain.toString().encodeToByteArray().toList()
            plain.clear()
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val escape = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3) else null
            when {
                escape != null && escape.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> {
                    flush()
                    bytes += escape.toInt(16).toByte()
                    i += 3
                }
                c == '+' -> { plain.append(' '); i++ }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return bytes.toByteArray().decodeToString()
    }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in "-_.~")) append(c) else append("%" + (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
