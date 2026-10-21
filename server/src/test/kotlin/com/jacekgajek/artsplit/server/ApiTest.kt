package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.api.PushResponse
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.RenameBand
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
        return ArtSplitJson.decodeFromString(serializer, body!!)
    }

    private fun createBand(user: String = "alice", bandId: String = "band1") = call(
        "POST", "/bands", user,
        ArtSplitJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest(bandId, "Night Owls", "PLN", "m-$user", user.replaceFirstChar { it.uppercase() })),
    )

    private var seq = 0L
    private fun push(bandId: String, user: String, vararg ops: Operation, clientId: String = "phone") = call(
        "POST", "/bands/$bandId/ops", user,
        ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(ops.map { OpEnvelope("op${++seq}", clientId, seq, seq, it) })),
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
        val request = ArtSplitJson.encodeToString(
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
        val band = ArtSplitJson.decodeFromString(Band.serializer(), store.objects.getValue(Api.bandKey("band1")).content)
        val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
        store.put(Api.bandKey("band1"), ArtSplitJson.encodeToString(Band.serializer(), linked))

        val response = push("band1", "bob", CreateEvent("e", "Gig", LocalDate(2026, 9, 13))).parse(PushResponse.serializer())
        assertEquals(RejectionReason.ROLE_REQUIRED, response.rejected.values.single().reason)
        assertTrue(response.band.events.isEmpty())
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
        val body = ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("a", "phone", 1, 1, RenameBand("X")))))
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
        "POST", "/bands/band1/ops", user, ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())),
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
