package com.jacekgajek.kapelusz.server.places

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.LocatedPlace
import com.jacekgajek.kapelusz.api.LocationsResponse
import com.jacekgajek.kapelusz.api.MapLinkRequest
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.server.ApiError
import com.jacekgajek.kapelusz.server.Call
import com.jacekgajek.kapelusz.server.HttpRequest
import com.jacekgajek.kapelusz.server.HttpResponse
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.badRequestIf
import com.jacekgajek.kapelusz.server.bands.BandAccess
import com.jacekgajek.kapelusz.server.ok
import com.jacekgajek.kapelusz.server.parse
import com.jacekgajek.kapelusz.server.store.GeocodeCache
import com.jacekgajek.kapelusz.server.store.Located
import com.jacekgajek.kapelusz.text.Places
import com.jacekgajek.kapelusz.text.PlayedPlaces
import java.util.concurrent.Executors
import kotlinx.serialization.json.put

/**
 * `GET /bands/{id}/places?q=…`, `GET /bands/{id}/places/{placeId}` and `POST /bands/{id}/places/resolve`: for whoever
 * may edit the band's events (leads up), so the key is not anyone's.
 */
internal class PlacesHandlers(private val bands: BandAccess, private val search: PlaceSearch?, private val geocode: GeocodeCache? = null) {
    private val language = Regex("[a-z]{2}(-[A-Z]{2})?")
    private val token = Regex("[A-Za-z0-9_-]{1,300}")

    fun suggest(call: Call): HttpResponse {
        val request = call.request
        val search = ready(call.user, call.param("bandId"))
        val query = request.query["q"].orEmpty().trim()
        badRequestIf(query.length !in Places.MIN_QUERY..Places.MAX_QUERY) { "Type ${Places.MIN_QUERY} to ${Places.MAX_QUERY} characters" }
        return ok(PlacesResponse.serializer(), PlacesResponse(search.suggest(query, request.language(), request.session())))
    }

    /** What Location becomes for a picked suggestion: its name and full address (I5 §9.1). */
    fun details(call: Call): HttpResponse {
        val request = call.request
        val placeId = call.param("placeId")
        val search = ready(call.user, call.param("bandId"))
        badRequestIf(!token.matches(placeId)) { "Not a place" }
        val place = search.details(placeId, request.language(), request.session()) ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No such place")
        val name = place.name ?: place.address ?: throw ApiError(404, ApiErrors.NOT_FOUND, "No such place")
        return ok(PlaceText.serializer(), PlaceText(Places.placeText(name, place.address, place.country)))
    }

    /**
     * What Location becomes for a Google Maps link (I5 §9.1): a place's name and the address at its pin, a
     * dropped pin's coordinates, or the searched text. 422 when the link points at nothing readable.
     */
    fun resolve(call: Call): HttpResponse {
        val request = call.request
        val link = call.body(MapLinkRequest.serializer())
        val search = ready(call.user, call.param("bandId"))
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
     * `GET /bands/{id}/locations` (I7 §8): where the event locations the user sees are, for any member. Only
     * the band's own locations, so nobody has addresses looked up at the band's cost; each address once, ever (cached);
     * at most [MAX_LOOKUPS] new ones a call, the rest pending, asked again by the app.
     */
    fun locations(call: Call): HttpResponse {
        val user = call.user
        val bandId = call.param("bandId")
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
