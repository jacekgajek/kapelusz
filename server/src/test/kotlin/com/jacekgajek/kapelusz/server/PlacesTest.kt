package com.jacekgajek.kapelusz.server

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
    private val search = PlaceSearch { query, language ->
        asked += query to language
        listOf(PlaceSuggestion("p1", "Stodoła", "Stefana Batorego 10, 02-591 Warszawa, Polska"))
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
}
