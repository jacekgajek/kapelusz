package com.jacekgajek.kapelusz.local

import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.server.places.PlaceAddress
import com.jacekgajek.kapelusz.server.places.PlaceSearch
import com.jacekgajek.kapelusz.server.store.Located
import java.util.Base64

/**
 * Stands in for Google's place services offline: what was typed comes back as a place in a made-up street, and an
 * address is somewhere around Kraków, always the same spot for the same text, so the map has pins to show.
 */
class OfflinePlaceSearch : PlaceSearch {

    override fun suggest(query: String, language: String?, session: String?): List<PlaceSuggestion> = listOf(PlaceSuggestion(ID_PREFIX + encoder.encodeToString(query.toByteArray()), query, ADDRESS))

    override fun details(placeId: String, language: String?, session: String?): PlaceAddress? {
        val name = placeId.removePrefix(ID_PREFIX).takeIf { placeId.startsWith(ID_PREFIX) }
            ?.let { runCatching { decoder.decode(it).decodeToString() }.getOrNull() } ?: return null
        return PlaceAddress(name, "$name, $ADDRESS", COUNTRY)
    }

    override fun address(latitude: String, longitude: String, language: String?) = PlaceAddress(null, "$ADDRESS ($latitude, $longitude)", COUNTRY)

    // Short links would have to be fetched from Google: offline they lead nowhere.
    override fun follow(url: String): String? = null

    override fun locate(address: String): Located {
        val hash = address.lowercase().hashCode()
        return Located(KRAKOW_LAT + (hash % 1000) / 10_000.0, KRAKOW_LNG + ((hash / 1000) % 1000) / 10_000.0)
    }

    private companion object {
        const val ID_PREFIX = "local-"
        const val ADDRESS = "Testowa 1, 30-001 Kraków, Poland"
        const val COUNTRY = "Poland"
        const val KRAKOW_LAT = 50.0614
        const val KRAKOW_LNG = 19.9366
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
