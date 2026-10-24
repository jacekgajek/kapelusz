package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.MapLinkRequest
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.OpEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ITERATION_5 §9: place suggestions for Location, through the server. */
class PlacesTest {
    private val store = InMemoryBlobStore()
    private val asked = mutableListOf<Pair<String, String?>>()
    private val followed = mutableListOf<String>()
    private val search = object : PlaceSearch {
        override fun suggest(query: String, language: String?, session: String?): List<PlaceSuggestion> {
            asked += query to language
            return listOf(PlaceSuggestion("p1", "Stodoła", "Stefana Batorego 10, 02-591 Warszawa, Polska"))
        }

        override fun details(placeId: String, language: String?, session: String?) =
            if (placeId == "olowek") PlaceAddress("Ołówek", "Legnicka 36, 53-673 Wrocław, Polska", "Polska") else null

        override fun address(latitude: String, longitude: String, language: String?) =
            if (latitude.startsWith("51.1")) PlaceAddress(null, "Legnicka 36, 53-673 Wrocław, Polska", "Polska") else null

        override fun follow(url: String): String? {
            followed += url
            return mapOf(
                "https://maps.app.goo.gl/olowek" to "https://www.google.com/maps/place/O%C5%82%C3%B3wek/@51.11,17.0,17z/data=!3m1!4b1!8m2!3d51.1167!4d17.0012",
                "https://maps.app.goo.gl/pin" to "https://www.google.com/maps/place/52%C2%B013'47.0%22N+21%C2%B000'43.9%22E/@52.2297,21.0122,17z/data=!8m2!3d52.2297222!4d21.0121944",
                "https://maps.app.goo.gl/sea" to "https://www.google.com/maps/place/Somewhere/@10.5,20.25,17z",
            )[url]
        }
    }

    private fun api(search: PlaceSearch?) = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, placeSearch = search)
    private val api = api(search)

    private fun call(user: String, query: Map<String, String>, method: String = "GET", path: String = "/bands/band1/places", body: String? = null, on: Api = api) =
        on.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body, query))

    /** Alice owns the band; Bob is a plain member. */
    private fun setup() {
        call("alice", emptyMap(), "POST", "/bands", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        call("alice", emptyMap(), "POST", "/bands/band1/ops", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob"))))))
        val invite = call("alice", emptyMap(), "POST", "/bands/band1/invites", KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest("m-bob", Role.MEMBER)))
        val code = KapeluszJson.decodeFromString(InviteInfo.serializer(), invite.body!!).code
        call("bob", emptyMap(), "POST", "/invites/$code/accept")
    }

    @Test
    fun aLeadGetsSuggestionsInTheirLanguage() {
        setup()
        val response = call("alice", mapOf("q" to " Stodo ", "lang" to "pl"))
        assertEquals(200, response.status, response.body)
        assertEquals("Stodoła", KapeluszJson.decodeFromString(PlacesResponse.serializer(), response.body!!).places.single().name)
        assertEquals(listOf<Pair<String, String?>>("Stodo" to "pl"), asked)
        call("alice", mapOf("q" to "Stodoła", "lang" to "x\"}"))
        assertEquals(null, asked.last().second, "a made-up language is not passed on")
    }

    @Test
    fun onlyLeadsOfTheBandSearchAndOnlyRealQueries() {
        setup()
        assertEquals(403, call("bob", mapOf("q" to "Stodoła")).status, "members don't edit events")
        assertEquals(404, call("mallory", mapOf("q" to "Stodoła")).status)
        assertEquals(400, call("alice", mapOf("q" to "St")).status)
        assertEquals(400, call("alice", mapOf("q" to "x".repeat(101))).status)
        assertTrue(asked.isEmpty(), "Google was never asked")
    }

    @Test
    fun withoutAKeyPlaceSearchIsUnavailable() {
        setup()
        val response = call("alice", mapOf("q" to "Stodoła"), on = api(null))
        assertEquals(503, response.status)
        assertTrue(response.body!!.contains(ApiErrors.PLACES_UNAVAILABLE))
    }

    private fun text(response: HttpResponse): String {
        assertEquals(200, response.status, response.body)
        return KapeluszJson.decodeFromString(PlaceText.serializer(), response.body!!).text
    }

    private fun resolve(user: String, url: String) =
        call(user, emptyMap(), "POST", "/bands/band1/places/resolve", KapeluszJson.encodeToString(MapLinkRequest.serializer(), MapLinkRequest(url)))

    @Test
    fun aPickedPlaceBecomesItsNameAndFullAddress() {
        setup()
        assertEquals("Ołówek, Legnicka 36, 53-673 Wrocław", text(call("alice", mapOf("lang" to "pl", "session" to "s-1"), path = "/bands/band1/places/olowek")))
        assertEquals(404, call("alice", emptyMap(), path = "/bands/band1/places/nothing").status)
        assertEquals(400, call("alice", emptyMap(), path = "/bands/band1/places/a%2Fb..").status)
        assertEquals(403, call("bob", emptyMap(), path = "/bands/band1/places/olowek").status)
    }

    @Test
    fun aMapsLinkBecomesTheAddressOrThePin() {
        setup()
        assertEquals("Ołówek, Legnicka 36, 53-673 Wrocław", text(resolve("alice", "https://maps.app.goo.gl/olowek")), "a place: its name and the address at its pin")
        assertEquals("52.229722, 21.012194", text(resolve("alice", " https://maps.app.goo.gl/pin ")), "a dropped pin: its coordinates")
        assertEquals("Somewhere, 10.500000, 20.250000", text(resolve("alice", "https://maps.app.goo.gl/sea")), "no address there: the name and the pin")
        assertEquals("Hala Stulecia", text(resolve("alice", "https://www.google.com/maps/search/?api=1&query=Hala+Stulecia")), "a searched text, read without following")
        assertEquals(false, followed.any { "Hala" in it })
        assertEquals("Ołówek, Legnicka 36, 53-673 Wrocław", text(resolve("alice", "https://www.google.com/maps/search/?api=1&query=place_id:olowek")), "a place by its id: its full address")
        assertEquals(422, resolve("alice", "https://www.google.com/maps/search/?api=1&query=place_id:a/b").status)
        assertEquals(422, resolve("alice", "https://maps.app.goo.gl/unknown").status)
        assertEquals(400, resolve("alice", "https://example.com/maps/place/X").status, "only Google Maps links are opened")
        assertEquals(403, resolve("bob", "https://maps.app.goo.gl/pin").status)
    }
}
