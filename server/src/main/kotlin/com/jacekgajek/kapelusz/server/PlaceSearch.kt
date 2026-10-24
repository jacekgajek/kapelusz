package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/** Places for what was typed in an event's Location (ITERATION_5 §9). */
fun interface PlaceSearch {
    fun suggest(query: String, language: String?): List<PlaceSuggestion>
}

/** Google's Places API (New) Autocomplete; the key stays on the server, restricted to that API. */
class GooglePlaceSearch(private val apiKey: String) : PlaceSearch {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun suggest(query: String, language: String?): List<PlaceSuggestion> {
        val body = buildJsonObject {
            put("input", query)
            language?.let { put("languageCode", it) }
        }
        val request = java.net.http.HttpRequest.newBuilder(URI("https://places.googleapis.com/v1/places:autocomplete"))
            .timeout(Duration.ofSeconds(5))
            .header("content-type", "application/json")
            .header("x-goog-api-key", apiKey)
            // Only what is shown: fewer fields, the cheaper request.
            .header("x-goog-fieldmask", "suggestions.placePrediction.placeId,suggestions.placePrediction.structuredFormat")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = runCatching { http.send(request, BodyHandlers.ofString()) }.getOrElse { unavailable("Place search did not answer") }
        if (response.statusCode() != 200) {
            System.err.println("Places API ${response.statusCode()}: ${response.body().take(300)}")
            unavailable("Place search failed")
        }
        // An answer of another shape is Google's failure too, not ours.
        return runCatching { parse(response.body()) }.getOrElse { unavailable("Place search answered oddly") }
    }

    private fun parse(body: String): List<PlaceSuggestion> {
        val suggestions = json.parseToJsonElement(body).jsonObject["suggestions"]?.jsonArray.orEmpty()
        return suggestions.mapNotNull { suggestion ->
            val prediction = suggestion.jsonObject["placePrediction"]?.jsonObject ?: return@mapNotNull null
            val format = prediction["structuredFormat"]?.jsonObject
            fun JsonObject.text(field: String) = this[field]?.jsonObject?.get("text")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            PlaceSuggestion(
                placeId = prediction["placeId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = format?.text("mainText") ?: return@mapNotNull null,
                address = format.text("secondaryText"),
            )
        }.take(MAX_SUGGESTIONS)
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
    }
}

private fun unavailable(message: String): Nothing = throw ApiError(503, ApiErrors.PLACES_UNAVAILABLE, message)

/** `GET /bands/{id}/places?q=…&lang=…`: for whoever may edit the band's events (leads up), so the key is not anyone's. */
internal class PlacesHandlers(private val bands: BandAccess, private val search: PlaceSearch?) {
    private val language = Regex("[a-z]{2}(-[A-Z]{2})?")

    fun suggest(user: VerifiedUser, bandId: String, request: HttpRequest): HttpResponse {
        val (band, _) = bands.loadForMember(user, bandId)
        if (!band.memberOfUser(user.userId)!!.role.atLeast(Role.LEAD)) throw ApiError(403, ApiErrors.FORBIDDEN, "Only who edits events searches places")
        val query = request.query["q"].orEmpty().trim()
        badRequestIf(query.length !in Places.MIN_QUERY..Places.MAX_QUERY) { "Type ${Places.MIN_QUERY} to ${Places.MAX_QUERY} characters" }
        val search = search ?: unavailable("Place search is not set up")
        return ok(PlacesResponse.serializer(), PlacesResponse(search.suggest(query, request.query["lang"]?.takeIf { language.matches(it) })))
    }
}
