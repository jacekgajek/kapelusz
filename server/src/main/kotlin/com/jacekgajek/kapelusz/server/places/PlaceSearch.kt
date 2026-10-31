package com.jacekgajek.kapelusz.server.places

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.HttpRequest
import com.jacekgajek.kapelusz.server.decode
import com.jacekgajek.kapelusz.server.encode
import com.jacekgajek.kapelusz.server.error
import com.jacekgajek.kapelusz.server.parse
import com.jacekgajek.kapelusz.server.store.Located
import com.jacekgajek.kapelusz.text.Places
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A place's address as Google gives it: the [name] (for a place), the full [address], and its [country] to leave out. */
data class PlaceAddress(val name: String?, val address: String?, val country: String?)

/** Google's place services for an event's Location. */
interface PlaceSearch {
    /** Places for what was typed; [session] groups a search with the details of the place picked. */
    fun suggest(query: String, language: String?, session: String?): List<PlaceSuggestion>

    /** A picked place's name and full address. */
    fun details(placeId: String, language: String?, session: String?): PlaceAddress?

    /** The address at a pin. */
    fun address(latitude: String, longitude: String, language: String?): PlaceAddress?

    /** Where a short Maps link leads (its redirects followed, Google's hosts only); null if it leads nowhere readable. */
    fun follow(url: String): String?

    /** Where an address is; null when Google knows no such place. Throws when it couldn't be asked. */
    fun locate(address: String): Located? = throw UnsupportedOperationException("No geocoding")
}

/** Places API (New) and the Geocoding API; the key stays on the server, restricted to those two. */
class GooglePlaceSearch(private val apiKey: String) : PlaceSearch {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun suggest(query: String, language: String?, session: String?): List<PlaceSuggestion> {
        val body = buildJsonObject {
            put("input", query)
            language?.let { put("languageCode", it) }
            session?.let { put("sessionToken", it) }
        }
        val request = google(URI("https://places.googleapis.com/v1/places:autocomplete"))
            .header("content-type", "application/json")
            // Only what is shown: fewer fields, the cheaper request.
            .header("x-goog-fieldmask", "suggestions.placePrediction.placeId,suggestions.placePrediction.structuredFormat")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return read(request) { root ->
            root["suggestions"]?.jsonArray.orEmpty().mapNotNull { suggestion ->
                val prediction = suggestion.jsonObject["placePrediction"]?.jsonObject ?: return@mapNotNull null
                val format = prediction["structuredFormat"]?.jsonObject
                PlaceSuggestion(
                    placeId = prediction["placeId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    name = format?.text("mainText") ?: return@mapNotNull null,
                    address = format.text("secondaryText"),
                )
            }.take(MAX_SUGGESTIONS)
        }
    }

    override fun details(placeId: String, language: String?, session: String?): PlaceAddress? {
        val query = listOfNotNull(language?.let { "languageCode=$it" }, session?.let { "sessionToken=$it" }).joinToString("&")
        val request = google(URI("https://places.googleapis.com/v1/places/$placeId" + (if (query.isEmpty()) "" else "?$query")))
            .header("x-goog-fieldmask", "displayName,formattedAddress,addressComponents")
            .GET()
            .build()
        return read(request) { root ->
            PlaceAddress(
                name = root.text("displayName"),
                address = root["formattedAddress"]?.jsonPrimitive?.content,
                country = (root["addressComponents"] as? JsonArray).country("types", "longText"),
            )
        }
    }

    override fun address(latitude: String, longitude: String, language: String?): PlaceAddress? {
        val query = listOfNotNull("latlng=$latitude,$longitude", language?.let { "language=$it" }, "key=" + URLEncoder.encode(apiKey, Charsets.UTF_8)).joinToString("&")
        val request = java.net.http.HttpRequest.newBuilder(URI("https://maps.googleapis.com/maps/api/geocode/json?$query")).timeout(TIMEOUT).GET().build()
        return read(request) { root ->
            val status = root["status"]?.jsonPrimitive?.content
            if (status != "OK") {
                if (status != "ZERO_RESULTS") System.err.println("Geocoding API $status: ${root["error_message"]?.jsonPrimitive?.content}")
                return@read null
            }
            val first = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@read null
            PlaceAddress(null, first["formatted_address"]?.jsonPrimitive?.content, (first["address_components"] as? JsonArray).country("types", "long_name"))
        }
    }

    override fun locate(address: String): Located? {
        val query = "address=" + URLEncoder.encode(address, Charsets.UTF_8) + "&key=" + URLEncoder.encode(apiKey, Charsets.UTF_8)
        val request = java.net.http.HttpRequest.newBuilder(URI("https://maps.googleapis.com/maps/api/geocode/json?$query")).timeout(TIMEOUT).GET().build()
        val response = http.send(request, BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Geocoding API answered ${response.statusCode()}" }
        val root = json.parseToJsonElement(response.body()).jsonObject
        return when (val status = root["status"]?.jsonPrimitive?.content) {
            "ZERO_RESULTS" -> null

            "OK" -> root["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("geometry")?.jsonObject?.get("location")?.jsonObject?.let { at ->
                Located(at.getValue("lat").jsonPrimitive.content.toDouble(), at.getValue("lng").jsonPrimitive.content.toDouble())
            }

            // Over the quota, a refused key: not an answer about the place, so not remembered.
            else -> error("Geocoding API $status: ${root["error_message"]?.jsonPrimitive?.content}")
        }
    }

    override fun follow(url: String): String? {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            // Outside Google's hosts nothing is fetched (no requests elsewhere on a user's behalf).
            if (!Places.isMapLink(current)) return null
            if (Places.target(current) != null) return current
            if (hop == MAX_REDIRECTS) return null
            // A link that is no valid URI (a space in it, say) leads nowhere.
            val uri = runCatching { URI(current) }.getOrNull() ?: return null
            val request = java.net.http.HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("user-agent", "Kapelusz").GET().build()
            val response = runCatching { http.send(request, BodyHandlers.discarding()) }.getOrNull() ?: return null
            val next = response.headers().firstValue("location").orElse(null)?.let { runCatching { uri.resolve(it).toString() }.getOrNull() } ?: return null
            // A visitor from the EU may be sent to Google's consent page first; where it continues to is the link.
            current = consentContinue(next) ?: next
        }
        return null
    }

    private fun consentContinue(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.host?.startsWith("consent.google.") != true) return null
        uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("continue=") }?.removePrefix("continue=")?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
    }.getOrNull()

    private fun google(uri: URI) = java.net.http.HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("x-goog-api-key", apiKey)

    /** Google's answer as JSON, read by [parse]; no answer, an error or another shape: place search is unavailable. */
    private fun <T> read(request: java.net.http.HttpRequest, parse: (JsonObject) -> T): T {
        val response = runCatching { http.send(request, BodyHandlers.ofString()) }.getOrElse { unavailable("Place search did not answer") }
        if (response.statusCode() != 200) {
            System.err.println("${request.uri().host} ${response.statusCode()}: ${response.body().take(300)}")
            unavailable("Place search failed")
        }
        // An answer of another shape is Google's failure too, not ours.
        return runCatching { parse(json.parseToJsonElement(response.body()).jsonObject) }.getOrElse { unavailable("Place search answered oddly") }
    }

    private fun JsonObject.text(field: String) = this[field]?.jsonObject?.get("text")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonArray?.country(types: String, name: String): String? = this?.firstOrNull { component ->
        component.jsonObject[types]?.jsonArray?.any { it.jsonPrimitive.content == "country" } == true
    }?.jsonObject?.get(name)?.jsonPrimitive?.content

    private companion object {
        const val MAX_SUGGESTIONS = 5
        const val MAX_REDIRECTS = 6
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

internal fun unavailable(message: String): Nothing = throw ApiError(503, ApiErrors.PLACES_UNAVAILABLE, message)
