package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.LocationsResponse
import com.jacekgajek.kapelusz.api.MapLinkRequest
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.api.PlaceText
import com.jacekgajek.kapelusz.api.PlacesResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.places.PlaceAddress
import com.jacekgajek.kapelusz.server.places.PlaceSearch
import com.jacekgajek.kapelusz.server.store.GeocodeCache
import com.jacekgajek.kapelusz.server.store.InMemoryBlobStore
import com.jacekgajek.kapelusz.server.store.InMemoryGeocodeCache
import com.jacekgajek.kapelusz.server.store.InMemoryHistoryStore
import com.jacekgajek.kapelusz.server.store.Located
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate

// One server with a fake place search, a fresh one per test.
private class PlacesFixture {
    val store = InMemoryBlobStore()
    val asked = mutableListOf<Pair<String, String?>>()
    val followed = mutableListOf<String>()
    val search = object : PlaceSearch {
        override fun suggest(query: String, language: String?, session: String?): List<PlaceSuggestion> {
            asked += query to language
            return listOf(PlaceSuggestion("p1", "Stodoła", "Stefana Batorego 10, 02-591 Warszawa, Polska"))
        }

        override fun details(placeId: String, language: String?, session: String?) = if (placeId == "olowek") {
            PlaceAddress("Ołówek", "Legnicka 36, 53-673 Wrocław, Polska", "Polska")
        } else {
            null
        }

        override fun address(latitude: String, longitude: String, language: String?) = if (latitude.startsWith("51.1")) {
            PlaceAddress(null, "Legnicka 36, 53-673 Wrocław, Polska", "Polska")
        } else {
            null
        }

        override fun follow(url: String): String? {
            followed += url
            return mapOf(
                "https://maps.app.goo.gl/olowek" to
                    "https://www.google.com/maps/place/O%C5%82%C3%B3wek/@51.11,17.0,17z/data=!3m1!4b1!8m2!3d51.1167!4d17.0012",
                "https://maps.app.goo.gl/pin" to
                    "https://www.google.com/maps/place/52%C2%B013'47.0%22N+21%C2%B000'43.9%22E/@52.2297,21.0122,17z/" +
                    "data=!8m2!3d52.2297222!4d21.0121944",
                "https://maps.app.goo.gl/sea" to "https://www.google.com/maps/place/Somewhere/@10.5,20.25,17z",
            )[url]
        }

        override fun locate(address: String): Located? {
            located += address
            if (address == "Broken") error("quota")
            return if (address.startsWith("Stodoła")) Located(52.2, 21.0) else null
        }
    }

    // Asked from parallel lookups.
    val located: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    fun api(search: PlaceSearch?) = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, placeSearch = search)
    val api = api(search)

    fun call(
        user: String,
        query: Map<String, String>,
        method: String = "GET",
        path: String = "/bands/band1/places",
        body: String? = null,
        on: Api = api,
    ) = on.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body, query))

    /** Alice owns the band; Bob is a plain member. */
    fun setup() {
        call(
            "alice",
            emptyMap(),
            "POST",
            "/bands",
            KapeluszJson.encodeToString(
                CreateBandRequest.serializer(),
                CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice"),
            ),
        )
        call(
            "alice",
            emptyMap(),
            "POST",
            "/bands/band1/ops",
            KapeluszJson.encodeToString(
                PushRequest.serializer(),
                PushRequest(listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob")))),
            ),
        )
        val invite = call(
            "alice",
            emptyMap(),
            "POST",
            "/bands/band1/invites",
            KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest("m-bob", Role.MEMBER)),
        )
        val code = KapeluszJson.decodeFromString(InviteInfo.serializer(), invite.body!!).code
        call("bob", emptyMap(), "POST", "/invites/$code/accept")
    }

    fun text(response: HttpResponse): String {
        withClue(response.body) { response.status shouldBe 200 }
        return KapeluszJson.decodeFromString(PlaceText.serializer(), response.body!!).text
    }

    fun resolve(user: String, url: String) = call(
        user,
        emptyMap(),
        "POST",
        "/bands/band1/places/resolve",
        KapeluszJson.encodeToString(MapLinkRequest.serializer(), MapLinkRequest(url)),
    )

    fun push(user: String, vararg ops: com.jacekgajek.kapelusz.ops.Operation) = call(
        user,
        emptyMap(),
        "POST",
        "/bands/band1/ops",
        KapeluszJson.encodeToString(
            PushRequest.serializer(),
            PushRequest(ops.mapIndexed { i, op -> OpEnvelope("map-$i", "map", i + 1L, 1, op) }),
        ),
    )

    fun locations(user: String, on: Api) = KapeluszJson.decodeFromString(
        LocationsResponse.serializer(),
        call(user, emptyMap(), path = "/bands/band1/locations", on = on).body!!,
    )
}

/** Place suggestions for Location, through the server. */
class PlacesTest :
    ExpectSpec({

        expect("a lead gets suggestions in their language") {
            with(PlacesFixture()) {
                setup()
                val response = call("alice", mapOf("q" to " Stodo ", "lang" to "pl"))
                withClue(response.body) { response.status shouldBe 200 }
                KapeluszJson.decodeFromString(PlacesResponse.serializer(), response.body!!).places.single().name shouldBe "Stodoła"
                asked shouldBe listOf<Pair<String, String?>>("Stodo" to "pl")
                call("alice", mapOf("q" to "Stodoła", "lang" to "x\"}"))
                withClue("a made-up language is not passed on") { asked.last().second shouldBe null }
            }
        }

        expect("only leads of the band search and only real queries") {
            with(PlacesFixture()) {
                setup()
                withClue("members don't edit events") { call("bob", mapOf("q" to "Stodoła")).status shouldBe 403 }
                call("mallory", mapOf("q" to "Stodoła")).status shouldBe 404
                call("alice", mapOf("q" to "St")).status shouldBe 400
                call("alice", mapOf("q" to "x".repeat(101))).status shouldBe 400
                withClue("Google was never asked") { asked.shouldBeEmpty() }
            }
        }

        expect("without a key place search is unavailable") {
            with(PlacesFixture()) {
                setup()
                val response = call("alice", mapOf("q" to "Stodoła"), on = api(null))
                response.status shouldBe 503
                response.body!! shouldContain ApiErrors.PLACES_UNAVAILABLE
            }
        }

        expect("a picked place becomes its name and full address") {
            with(PlacesFixture()) {
                setup()
                text(call("alice", mapOf("lang" to "pl", "session" to "s-1"), path = "/bands/band1/places/olowek")) shouldBe
                    "Ołówek, Legnicka 36, 53-673 Wrocław"
                call("alice", emptyMap(), path = "/bands/band1/places/nothing").status shouldBe 404
                call("alice", emptyMap(), path = "/bands/band1/places/a%2Fb..").status shouldBe 400
                call("bob", emptyMap(), path = "/bands/band1/places/olowek").status shouldBe 403
            }
        }

        expect("a Maps link becomes the address or the pin") {
            with(PlacesFixture()) {
                setup()
                withClue("a place: its name and the address at its pin") {
                    text(resolve("alice", "https://maps.app.goo.gl/olowek")) shouldBe "Ołówek, Legnicka 36, 53-673 Wrocław"
                }
                withClue("a dropped pin: its coordinates") {
                    text(resolve("alice", " https://maps.app.goo.gl/pin ")) shouldBe "52.229722, 21.012194"
                }
                withClue("no address there: the name and the pin") {
                    text(resolve("alice", "https://maps.app.goo.gl/sea")) shouldBe "Somewhere, 10.500000, 20.250000"
                }
                withClue("a searched text, read without following") {
                    text(resolve("alice", "https://www.google.com/maps/search/?api=1&query=Hala+Stulecia")) shouldBe "Hala Stulecia"
                }
                followed.any { "Hala" in it } shouldBe false
                withClue("a place by its id: its full address") {
                    text(resolve("alice", "https://www.google.com/maps/search/?api=1&query=place_id:olowek")) shouldBe
                        "Ołówek, Legnicka 36, 53-673 Wrocław"
                }
                resolve("alice", "https://www.google.com/maps/search/?api=1&query=place_id:a/b").status shouldBe 422
                resolve("alice", "https://maps.app.goo.gl/unknown").status shouldBe 422
                withClue("only Google Maps links are opened") { resolve("alice", "https://example.com/maps/place/X").status shouldBe 400 }
                resolve("bob", "https://maps.app.goo.gl/pin").status shouldBe 403
            }
        }

        // The band's own locations, each address looked up once ever, pins never.
        expect("locations are looked up once and cached") {
            with(PlacesFixture()) {
                val cache = InMemoryGeocodeCache()
                val api = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, placeSearch = search, geocodeCache = cache)
                setup()
                push(
                    "alice",
                    CreateEvent("e1", "Gig", LocalDate(2026, 5, 1), location = "Stodoła, Batorego 10, Warszawa", participants = listOf(Participant("m-alice"), Participant("m-bob"))),
                    CreateEvent("e2", "Again", LocalDate(2026, 6, 1), location = "  stodoła,  Batorego 10, warszawa "),
                    CreateEvent("e3", "Field", LocalDate(2026, 7, 1), location = "Somewhere, 51.100000, 17.000000"),
                    CreateEvent("e4", "Nowhere", LocalDate(2026, 8, 1), location = "Atlantis"),
                    CreateEvent("e5", "Broken", LocalDate(2026, 8, 2), location = "Broken"),
                )
                val first = locations("bob", api)
                first.located.map { it.location }.sorted() shouldBe
                    listOf("Somewhere, 51.100000, 17.000000", "Stodoła, Batorego 10, Warszawa")
                first.notFound shouldBe listOf("Atlantis")
                withClue("a failed lookup is not remembered") { first.pending shouldBe listOf("Broken") }
                withClue("each address once (spelling aside), the pin never") {
                    located.sorted() shouldBe listOf("Atlantis", "Broken", "Stodoła, Batorego 10, Warszawa")
                }

                located.clear()
                val again = locations("alice", api)
                again.located.toSet() shouldBe first.located.toSet()
                withClue("only what wasn't answered is asked again") { located shouldBe listOf("Broken") }
                call("mallory", emptyMap(), path = "/bands/band1/locations", on = api).status shouldBe 404
            }
        }

        // The cache unreachable (e.g. before its table exists): pins still answered, nothing looked up and paid in vain.
        expect("a broken cache answers pins and looks nothing up") {
            with(PlacesFixture()) {
                val broken = object : GeocodeCache {
                    override fun get(keys: Collection<String>): Map<String, Located?> = error("no table")
                    override fun put(answers: Map<String, Located?>) = error("no table")
                }
                val api = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, placeSearch = search, geocodeCache = broken)
                setup()
                push(
                    "alice",
                    CreateEvent("e1", "Gig", LocalDate(2026, 5, 1), location = "Stodoła, Batorego 10, Warszawa"),
                    CreateEvent("e2", "Field", LocalDate(2026, 7, 1), location = "Somewhere, 51.100000, 17.000000"),
                    CreateEvent("e3", "Someday", LocalDate(2099, 1, 1), location = "Future Hall"),
                )
                val answer = locations("alice", api)
                answer.located.map { it.location } shouldBe listOf("Somewhere, 51.100000, 17.000000")
                withClue("a gig not played yet isn't even asked") { answer.pending shouldBe listOf("Stodoła, Batorego 10, Warszawa") }
                located.shouldBeEmpty()
            }
        }
    })
