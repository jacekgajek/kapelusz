package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameBand
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val store = InMemoryBlobStore()

    /** Tokens are just user ids in tests. */
    private val api = Api(store, { token -> VerifiedUser(token, "$token@example.com", token) }, clock = { 1_790_000_000_000 })

    private fun call(method: String, path: String, user: String? = "alice", body: String? = null, headers: Map<String, String> = emptyMap()) =
        api.handle(HttpRequest(method, path, headers + listOfNotNull(user?.let { "authorization" to "Bearer $it" }), body))

    private fun <T> HttpResponse.parse(serializer: KSerializer<T>): T {
        assertEquals(200, status, body)
        return KapeluszJson.decodeFromString(serializer, body!!)
    }

    private fun createBand(user: String = "alice", bandId: String = "band1") = call(
        "POST", "/bands", user,
        KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest(bandId, "Night Owls", "PLN", "m-$user", user.replaceFirstChar { it.uppercase() })),
    )

    private var seq = 0L
    private fun push(bandId: String, user: String, vararg ops: Operation, clientId: String = "phone") = call(
        "POST", "/bands/$bandId/ops", user,
        KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops.map { OpEnvelope("op${++seq}", clientId, seq, seq, it) })),
    )

    @Test
    fun requiresValidToken() {
        assertEquals(401, call("GET", "/me", user = null).status)
        val rejecting = Api(store, { null })
        assertEquals(401, rejecting.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer x"))).status)
    }

    @Test
    fun createBandThenListAndFetchState() {
        val created = createBand().parse(BandStateResponse.serializer())
        assertEquals("alice", created.band.owner?.userId)
        assertEquals(Role.OWNER, created.band.member("m-alice")?.role)

        val me = call("GET", "/me").parse(MeResponse.serializer())
        assertEquals(listOf("band1"), me.bands.map { it.id })
        assertEquals("Alice", me.bands.single().memberName)

        val state = call("GET", "/bands/band1/state").parse(BandStateResponse.serializer())
        assertEquals(created.version, state.version)
        assertEquals(304, call("GET", "/bands/band1/state", headers = mapOf("if-none-match" to state.version)).status)
    }

    @Test
    fun retriedCreateIsIdempotentButForeignIdConflicts() {
        assertEquals(200, createBand().status)
        assertEquals(200, createBand().status, "same owner retrying")
        assertEquals(409, createBand(user = "mallory").status)
    }

    @Test
    fun pushAppliesOpsIdempotentlyAndKeepsHistory() {
        createBand()
        val request = KapeluszJson.encodeToString(
            PushRequest.serializer(),
            PushRequest(listOf(OpEnvelope("a", "phone", 1, 1, RenameBand("Renamed")), OpEnvelope("b", "phone", 2, 2, AddMember("bob", "Bob")))),
        )
        val first = call("POST", "/bands/band1/ops", body = request).parse(PushResponse.serializer())
        assertEquals(listOf("a", "b"), first.accepted)
        assertEquals("Renamed", first.band.name)

        val retry = call("POST", "/bands/band1/ops", body = request).parse(PushResponse.serializer())
        assertEquals(listOf("a", "b"), retry.duplicates)
        assertEquals(2, retry.band.members.size)
        assertTrue(retry.band.lastSeq.keys.all { it.startsWith("alice/") }, "sequence numbers are per user and device")

        assertEquals(1, store.objects.keys.count { it.startsWith("bands/band1/log/2026-09/") })
    }

    @Test
    fun permissionsAreEnforcedOnTheServer() {
        createBand()
        // Bob joins as a plain member, as if he accepted an invite (a server-internal operation).
        push("band1", "alice", AddMember("m-bob", "Bob"))
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band1")).content)
        val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
        store.put(Api.bandKey("band1"), KapeluszJson.encodeToString(Band.serializer(), linked))

        val response = push("band1", "bob", CreateEvent("e", "Gig", LocalDate(2026, 9, 13))).parse(PushResponse.serializer())
        assertEquals(RejectionReason.ROLE_REQUIRED, response.rejected.values.single().reason)
        assertTrue(response.band.events.isEmpty())
    }

    @Test
    fun theGooglePhotoReachesTheUsersMembersAtSignIn() {
        val photo = "https://lh3.googleusercontent.com/a/alice-photo=s96-c"
        // Signing in with Google brings the photo; the test verifier stands in for Google.
        val google = Api(store, { token -> VerifiedUser(token.removeSuffix("-google"), null, null, picture = photo.takeIf { token.endsWith("-google") }) }, clock = { 1_790_000_000_000 })
        createBand()
        assertEquals(200, google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer alice-google"))).status)
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band1")).content)
        assertEquals(photo, band.memberOfUser("alice")?.pictureUrl)

        // A band created afterwards gets it too.
        createBand(bandId = "band2")
        assertEquals(photo, KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band2")).content).memberOfUser("alice")?.pictureUrl)

        // Anything but a Google photo address is ignored.
        val other = Api(store, { token -> VerifiedUser(token, null, null, picture = "https://evil.example/x.png") }, clock = { 1_790_000_000_000 })
        other.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer alice")))
        assertEquals(photo, KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band1")).content).memberOfUser("alice")?.pictureUrl)
    }

    @Test
    fun anAlreadySignedInUserSendsTheirKeptGoogleTokenForThePhoto() {
        val photo = "https://lh3.googleusercontent.com/a/alice-photo=s96-c"
        val google = Api(store, object : TokenVerifier {
            override fun verify(token: String) = VerifiedUser(token, null, null)
            override fun pictureOf(token: String, userId: String) = photo.takeIf { token == "old-google-token-of-$userId" }
        }, clock = { 1_790_000_000_000 })
        createBand()
        fun send(user: String, token: String) = google.handle(HttpRequest("POST", "/me/picture", mapOf("authorization" to "Bearer $user"), """{"googleToken":"$token"}"""))
        assertEquals(400, send("alice", "old-google-token-of-bob").status, "someone else's token")
        assertEquals(204, send("alice", "old-google-token-of-alice").status)
        assertEquals(photo, KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band1")).content).memberOfUser("alice")?.pictureUrl)
    }

    @Test
    fun aPushThatWouldMakeTheBandTooLargeToLoadIsRefused() {
        val small = Api(store, { token -> VerifiedUser(token, null, null) }, clock = { 1_790_000_000_000 }, maxBandChars = 3_000)
        createBand()
        fun add(i: Int) = small.handle(HttpRequest("POST", "/bands/band1/ops", mapOf("authorization" to "Bearer alice"),
            KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("c$i", "phone", 100L + i, 0, AddCost("c$i", "m-alice", com.jacekgajek.kapelusz.model.Money(100), LocalDate(2026, 9, 1), description = "d".repeat(400))))))))
        val statuses = (1..10).map { add(it).status }
        assertEquals(200, statuses.first())
        assertEquals(413, statuses.last(), statuses.toString())
        assertTrue(store.objects.getValue(Api.bandKey("band1")).content.length <= 3_000, "nothing above the cap was written")
    }

    @Test
    fun adminsGetTheBandFileExactlyAsStored() {
        createBand()
        push("band1", "alice", AddMember("m-bob", "Bob"), CreateEvent("e", "Gig, \"Łódź\"", LocalDate(2026, 9, 13)))
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band1")).content)
        val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
        store.put(Api.bandKey("band1"), KapeluszJson.encodeToString(Band.serializer(), linked))

        val raw = call("GET", "/bands/band1/raw", "alice")
        assertEquals(200, raw.status)
        assertEquals(store.objects.getValue(Api.bandKey("band1")).content, raw.body, "byte for byte, so it can be put back")
        assertEquals(403, call("GET", "/bands/band1/raw", "bob").status, "members do not")
        assertEquals(404, call("GET", "/bands/band1/raw", "eve").status, "outsiders cannot tell the band exists")
    }

    @Test
    fun outsidersCannotSeeOrChangeTheBand() {
        createBand()
        assertEquals(404, call("GET", "/bands/band1/state", user = "mallory").status)
        assertEquals(404, push("band1", "mallory", RenameBand("Hacked")).status)
        assertEquals(404, call("GET", "/bands/nope/state").status)
    }

    @Test
    fun concurrentWriteIsRetried() {
        createBand()
        var failOnce = true
        val flaky = object : BlobStore by store {
            override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
                if (failOnce && key == Api.bandKey("band1")) {
                    failOnce = false
                    throw PreconditionFailed()
                }
                return store.put(key, content, ifMatch, ifNoneMatch)
            }
        }
        val flakyApi = Api(flaky, { VerifiedUser(it, null, null) })
        val body = KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("a", "phone", 1, 1, RenameBand("X")))))
        val response = flakyApi.handle(HttpRequest("POST", "/bands/band1/ops", mapOf("authorization" to "Bearer alice"), body))
        assertEquals(listOf("a"), response.parse(PushResponse.serializer()).accepted)
    }

    @Test
    fun rejectsBadInput() {
        assertEquals(400, call("POST", "/bands", body = "{ nope").status)
        assertEquals(400, createBand(bandId = "../etc").status)
        assertEquals(404, call("GET", "/nothing").status)
    }

    private fun pushAs(user: String, vararg envelopes: OpEnvelope) = call(
        "POST", "/bands/band1/ops", user, KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())),
    )

    @Test
    fun deviceIdsAreBoundedSoNobodyCanBloatTheBand() {
        createBand()
        val long = "x".repeat(65)
        assertEquals(400, pushAs("alice", OpEnvelope("a", long, 1, 1, RenameBand("X"))).status)
        assertEquals(400, pushAs("alice", OpEnvelope("a", "phone", 1, 1, RenameBand("X")), OpEnvelope("b", "tablet", 1, 1, RenameBand("Y"))).status)

        repeat(Api.MAX_DEVICES_PER_USER) { i -> assertEquals(200, pushAs("alice", OpEnvelope("op$i", "device-$i", 1, 1, RenameBand("N$i"))).status) }
        val tooMany = pushAs("alice", OpEnvelope("over", "device-new", 1, 1, RenameBand("Z")))
        assertEquals(400, tooMany.status)
        assertTrue("too_many_devices" in tooMany.body!!)
        assertEquals(200, pushAs("alice", OpEnvelope("again", "device-3", 2, 2, RenameBand("Known device"))).status)
    }

    @Test
    fun missingBandHasItsOwnErrorCode() {
        val response = call("GET", "/bands/nope/state")
        assertEquals(404, response.status)
        assertTrue(Api.BAND_NOT_FOUND in response.body!!)
        assertTrue(Api.BAND_NOT_FOUND !in call("GET", "/no/such/route").body!!)
    }

    @Test
    fun answersCorsPreflightWithoutToken() {
        assertEquals(204, call("OPTIONS", "/me", user = null).status)
        assertEquals(204, call("OPTIONS", "/bands/band1/ops", user = null).status)
    }
}
