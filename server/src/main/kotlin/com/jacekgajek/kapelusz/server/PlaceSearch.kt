package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.LocatedPlace
import com.jacekgajek.kapelusz.api.LocationsResponse
import com.jacekgajek.kapelusz.api.MapLinkRequest
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.domain.PlayedPlaces
import com.jacekgajek.kapelusz.model.Role
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.Executors
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

/** Google's place services for an event's Location (ITERATION_5 §9). */
interface PlaceSearch {
    /** Places for what was typed; [session] groups a search with the details of the place picked. */
    fun suggest(query: String, language: String?, session: String?): List<PlaceSuggestion>

    /** A picked place's name and full address. */
    fun details(placeId: String, language: String?, session: String?): PlaceAddress?

    /** The address at a pin. */
    fun address(latitude: String, longitude: String, language: String?): PlaceAddress?

    /** Where a short Maps link leads (its redirects followed, Google's hosts only); null if it leads nowhere readable. */
    fun follow(url: String): String?

    /** Where an address is (ITERATION_7 §8); null when Google knows no such place. Throws when it couldn't be asked. */
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

private fun unavailable(message: String): Nothing = throw ApiError(503, ApiErrors.PLACES_UNAVAILABLE, message)

/**
 * `GET /bands/{id}/places?q=…`, `GET /bands/{id}/places/{placeId}` and `POST /bands/{id}/places/resolve`: for whoever
 * may edit the band's events (leads up), so the key is not anyone's.
 */
internal class PlacesHandlers(private val bands: BandAccess, private val search: PlaceSearch?, private val geocode: GeocodeCache? = null) {
    private val language = Regex("[a-z]{2}(-[A-Z]{2})?")
    private val token = Regex("[A-Za-z0-9_-]{1,300}")

    fun suggest(user: VerifiedUser, bandId: String, request: HttpRequest): HttpResponse {
        val search = ready(user, bandId)
        val query = request.query["q"].orEmpty().trim()
        badRequestIf(query.length !in Places.MIN_QUERY..Places.MAX_QUERY) { "Type ${Places.MIN_QUERY} to ${Places.MAX_QUERY} characters" }
        return ok(PlacesResponse.serializer(), PlacesResponse(search.suggest(query, request.language(), request.session())))
    }

    /** What Location becomes for a picked suggestion: its name and full address (ITERATION_5 §9.1). */
    fun details(user: VerifiedUser, bandId: String, placeId: String, request: HttpRequest): HttpResponse {
        val search = ready(user, bandId)
        badRequestIf(!token.matches(placeId)) { "Not a place" }
        val place = search.details(placeId, request.language(), request.session()) ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No such place")
        val name = place.name ?: place.address ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No such place")
        return ok(PlaceText.serializer(), PlaceText(Places.placeText(name, place.address, place.country)))
    }

    /**
     * What Location becomes for a Google Maps link (ITERATION_5 §9.1): a place's name and the address at its pin, a
     * dropped pin's coordinates, or the searched text. 422 when the link points at nothing readable.
     */
    fun resolve(user: VerifiedUser, bandId: String, request: HttpRequest, link: MapLinkRequest): HttpResponse {
        val search = ready(user, bandId)
        val url = link.url.trim()
        badRequestIf(url.length > MAX_LINK || !Places.isMapLink(url)) { "Not a Google Maps link" }
        // A full link is read as it is; a short one (the app's Share) is followed to the full one first.
        val target = (Places.target(url) ?: search.follow(url)?.let(Places::target)) ?: unreadable()
        val pin = target.pin
        val text = when {
            target.name != null -> {
                val address = pin?.let { search.address(it.first, it.second, request.language()) }
                when {
                    address?.address != null -> Places.placeText(target.name!!, address.address, address.country)
                    pin != null -> "${target.name}, ${coordinates(pin)}"
                    else -> target.name!!
                }
            }

            // A link to a place by its id: its name and full address, as for a picked suggestion.
            target.query?.startsWith("place_id:") == true -> search.details(target.query!!.removePrefix("place_id:").takeIf { token.matches(it) } ?: unreadable(), request.language(), null)
                ?.let { place -> (place.name ?: place.address)?.let { Places.placeText(it, place.address, place.country) } } ?: unreadable()

            target.query != null -> target.query!!

            pin != null -> coordinates(pin)

            else -> unreadable()
        }
        return ok(PlaceText.serializer(), PlaceText(text))
    }

    /**
     * `GET /bands/{id}/locations` (ITERATION_7 §8): where the event locations the user sees are, for any member. Only
     * the band's own locations, so nobody has addresses looked up at the band's cost; each address once, ever (cached);
     * at most [MAX_LOOKUPS] new ones a call, the rest pending, asked again by the app.
     */
    fun locations(user: VerifiedUser, bandId: String): HttpResponse {
        val (band, _) = bands.loadForMember(user, bandId)
        // Only concerts already played (a day's slack for time zones): the map shows no gigs to come.
        val tomorrow = kotlinx.datetime.LocalDate.parse(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString())
        val events = PlayedPlaces.visible(band, band.memberOfUser(user.userId)).filter { it.date <= tomorrow }
        val all = events.mapNotNull { it.location?.takeIf(String::isNotBlank) }.distinctBy(PlayedPlaces::key)
        val located = all.mapNotNull { location -> Places.pinIn(location)?.let { LocatedPlace(location, it.first, it.second) } }
        val addresses = PlayedPlaces.toLocate(events).associateBy { GeocodeCache.key(PlayedPlaces.key(it)) }
        val search = search
        val cache = geocode
        if (search == null || cache == null) return ok(LocationsResponse.serializer(), LocationsResponse(located, pending = addresses.values.toList()))
        // The cache unreadable: pins only, the rest pending, nothing looked up (it couldn't be kept, so it would be paid again).
        val known = runCatching { cache.get(addresses.keys) }.onFailure { System.err.println("Geocode cache unreadable: ${it.message}") }
            .getOrElse { return ok(LocationsResponse.serializer(), LocationsResponse(located, pending = addresses.values.toList())) }
            .toMutableMap()
        val asked = (addresses.keys - known.keys).take(MAX_LOOKUPS)
        val answers = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            asked.map { key -> key to pool.submit<Located?> { search.locate(addresses.getValue(key)) } }
                .mapNotNull { (key, answer) -> runCatching { key to answer.get() }.onFailure { System.err.println("Geocoding failed: ${it.message}") }.getOrNull() }
                .toMap()
        }
        // Not kept (the table unwritable): answered all the same.
        if (answers.isNotEmpty()) runCatching { cache.put(answers) }.onFailure { System.err.println("Geocode cache unwritable: ${it.message}") }
        known += answers
        return ok(
            LocationsResponse.serializer(),
            LocationsResponse(
                located = located + addresses.mapNotNull { (key, location) -> known[key]?.let { LocatedPlace(location, it.latitude, it.longitude) } },
                notFound = addresses.filter { (key, _) -> key in known && known[key] == null }.values.toList(),
                pending = addresses.filterKeys { it !in known }.values.toList(),
            ),
        )
    }

    private fun unreadable(): Nothing = throw ApiError(422, ApiErrors.BAD_REQUEST, "Could not read the link")

    private fun ready(user: VerifiedUser, bandId: String): PlaceSearch {
        val (band, _) = bands.loadForMember(user, bandId)
        if (!band.memberOfUser(user.userId)!!.role.atLeast(Role.LEAD)) throw ApiError(403, ApiErrors.FORBIDDEN, "Only who edits events searches places")
        return search ?: unavailable("Place search is not set up")
    }

    private fun HttpRequest.language() = query["lang"]?.takeIf { language.matches(it) }
    private fun HttpRequest.session() = query["session"]?.takeIf { token.matches(it) }

    /** A pin as Location shows it: 6 decimals (about 10 cm), Navigate's destination as it is. */
    private fun coordinates(pin: Pair<String, String>): String = Places.pinText(pin.first.toDouble(), pin.second.toDouble())

    private companion object {
        const val MAX_LINK = 4000
        const val MAX_LOOKUPS = 25
    }
}
