package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.history.BandPart
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.server.auth.TokenVerifier
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.InMemoryHistoryStore
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import com.jacekgajek.kapelusz.server.store.StorageKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class ApiTest : ApiHarness(verifier = { token -> VerifiedUser(token, "$token@example.com", token) }) {
    @Test
    fun requiresValidToken() {
        assertEquals(401, call("GET", "/me", user = null).status)
        val rejecting = Api(store, InMemoryHistoryStore(), { null })
        assertEquals(401, rejecting.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer x"))).status)
    }

    @Test
    fun eventLinksAreNotFoundWithoutTheWebPage() {
        // CloudFront then shows the web app's page (ITERATION_6 §2), which it cannot do for a 401.
        assertEquals(404, call("GET", "/e/0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000", user = null).status)
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

        // One entry for the push (a record per operation); the retried duplicates add nothing.
        val records = history.bands.getValue("band1").keys
        assertEquals(2, records.size)
        assertEquals(1, records.map { it.substringBefore('#') }.distinct().count { it.startsWith("2026-09/") })
    }

    @Test
    fun permissionsAreEnforcedOnTheServer() {
        createBand()
        // Bob joins as a plain member, as if he accepted an invite (a server-internal operation).
        push("alice", AddMember("m-bob", "Bob"), bandId = "band1")
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
        val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
        store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(Band.serializer(), linked))

        val response = push("bob", CreateEvent("e", "Gig", LocalDate(2026, 9, 13)), bandId = "band1").parse(PushResponse.serializer())
        assertEquals(RejectionReason.ROLE_REQUIRED, response.rejected.values.single().reason)
        assertTrue(response.band.events.isEmpty())
    }

    @Test
    fun theGooglePhotoReachesTheUsersMembersAtSignIn() {
        val photo = "https://lh3.googleusercontent.com/a/alice-photo=s96-c"
        // Signing in with Google brings the photo; the test verifier stands in for Google.
        val google = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token.removeSuffix("-google"), null, null, picture = photo.takeIf { token.endsWith("-google") }) }, clock = { 1_790_000_000_000 })
        createBand()
        assertEquals(200, google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer alice-google"))).status)
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
        assertEquals(photo, band.memberOfUser("alice")?.pictureUrl)

        // A band created afterwards gets it too.
        createBand(bandId = "band2")
        assertEquals(photo, KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band2")).content).memberOfUser("alice")?.pictureUrl)

        // Anything but a Google photo address is ignored.
        val other = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null, picture = "https://evil.example/x.png") }, clock = { 1_790_000_000_000 })
        other.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer alice")))
        assertEquals(photo, KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content).memberOfUser("alice")?.pictureUrl)
    }

    @Test
    fun anAlreadySignedInUserSendsTheirKeptGoogleTokenForThePhoto() {
        val photo = "https://lh3.googleusercontent.com/a/alice-photo=s96-c"
        val google = Api(
            store,
            InMemoryHistoryStore(),
            object : TokenVerifier {
                override fun verify(token: String) = VerifiedUser(token, null, null)
                override fun pictureOf(token: String, userId: String) = photo.takeIf { token == "old-google-token-of-$userId" }
            },
            clock = { 1_790_000_000_000 },
        )
        createBand()
        fun send(user: String, token: String) = google.handle(HttpRequest("POST", "/me/picture", mapOf("authorization" to "Bearer $user"), """{"googleToken":"$token"}"""))
        assertEquals(400, send("alice", "old-google-token-of-bob").status, "someone else's token")
        assertEquals(204, send("alice", "old-google-token-of-alice").status)
        assertEquals(photo, KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content).memberOfUser("alice")?.pictureUrl)
    }

    @Test
    fun outsidersCannotMakeTheServerReadTheLogAndUndosAreBounded() {
        createBand()
        fun undos(user: String, n: Int) = call(
            "POST",
            "/bands/band1/ops",
            user,
            KapeluszJson.encodeToString(
                PushRequest.serializer(),
                PushRequest(
                    (1..n).map { OpEnvelope("u$it", "phone", 900L + it, 0, Undo("x$it", RenameBand("x"), BandChange(BandPart(), BandPart()), "2026-01/1-00000000-0000-0000-0000-000000000000.json")) },
                ),
            ),
        )
        assertEquals(404, undos("mallory", 5).status, "not a member: refused before any undo lookup")
        assertEquals(400, undos("alice", Api.MAX_UNDOS_PER_PUSH + 1).status)
    }

    @Test
    fun oneAccountMayBeInAtMost20Bands() {
        repeat(Api.MAX_BANDS_PER_USER) { assertEquals(200, createBand(bandId = "b$it").status) }
        assertEquals(409, createBand(bandId = "one-more").status)
    }

    /** Only bands the account is still in count; an archived member is not shown the band (ITERATION_3 R6, R9). */
    @Test
    fun bandsTheAccountLeftDoNotCountTowardsTheLimit() {
        createBand("alice", "shared")
        push("alice", com.jacekgajek.kapelusz.ops.AddMember("m-bob", "Bob"), bandId = "shared")
        val invite = call(
            "POST",
            "/bands/shared/invites",
            "alice",
            KapeluszJson.encodeToString(
                com.jacekgajek.kapelusz.api.CreateInviteRequest.serializer(),
                com.jacekgajek.kapelusz.api.CreateInviteRequest("m-bob", com.jacekgajek.kapelusz.model.Role.MEMBER),
            ),
        ).parse(com.jacekgajek.kapelusz.api.InviteInfo.serializer())
        assertEquals(200, call("POST", "/invites/${invite.code}/accept", "bob").status)
        repeat(Api.MAX_BANDS_PER_USER - 1) { assertEquals(200, createBand("bob", "bob$it").status) }
        assertEquals(409, createBand("bob", "one-more").status)

        push("alice", com.jacekgajek.kapelusz.ops.SetMemberArchived("m-bob", true), bandId = "shared")
        assertTrue(call("GET", "/me", "bob").parse(com.jacekgajek.kapelusz.api.MeResponse.serializer()).bands.none { it.id == "shared" }, "archived: not listed")
        assertEquals(409, createBand("bob", "one-more").status, "archived members can be restored: the band still counts")

        val restored = com.jacekgajek.kapelusz.ops.SetMemberArchived("m-bob", false)
        push("alice", restored, com.jacekgajek.kapelusz.ops.UnlinkUser("m-bob"), bandId = "shared")
        assertEquals(200, createBand("bob", "one-more").status, "unlinked: the band no longer counts")
    }

    @Test
    fun bandCreationKeepsToTheLimits() {
        fun create(bandId: String, ownerMemberId: String, name: String = "Night Owls") = call(
            "POST",
            "/bands",
            "alice",
            KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest(bandId, name, "PLN", ownerMemberId, "Alice")),
        )
        assertEquals(400, create("../evil", "m-alice").status)
        assertEquals(400, create("band2", "band-fund").status)
        assertEquals(400, create("band3", "m-alice", name = "x".repeat(200)).status)
        assertEquals(200, create("band4", "m-alice").status)
    }

    @Test
    fun aPushThatWouldMakeTheBandTooLargeToLoadIsRefused() {
        val verifier = TokenVerifier { token -> VerifiedUser(token, null, null) }
        val small = Api(store, InMemoryHistoryStore(), verifier, clock = { 1_790_000_000_000 }, config = SMALL_CAP)
        createBand()
        fun add(i: Int) = small.handle(
            HttpRequest(
                "POST",
                "/bands/band1/ops",
                mapOf("authorization" to "Bearer alice"),
                KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("c$i", "phone", 100L + i, 0, AddCost("c$i", "m-alice", com.jacekgajek.kapelusz.model.Money(100), LocalDate(2026, 9, 1), description = "d".repeat(400)))))),
            ),
        )
        val responses = (1..10).map { add(it) }
        assertTrue(responses.all { it.status == 200 }, "the push goes through; only the operation is refused")
        val last = KapeluszJson.decodeFromString(PushResponse.serializer(), responses.last().body!!)
        assertEquals(RejectionReason.LIMIT_REACHED, last.rejected.values.single().reason)
        // Refused operations are remembered (for retried pushes, at most 20), so the file may pass the cap by that.
        val stored = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
        assertTrue(stored.cost("c10") == null, "the operation that would pass the cap was not applied")
        assertTrue(store.objects.getValue(StorageKeys.band("band1")).content.length < 4_000)
    }

    /** V5-M1 A2: the cap counts bytes; text in other scripts takes up to 3 bytes a character. */
    @Test
    fun theSizeCapCountsBytes() {
        val verifier = TokenVerifier { token -> VerifiedUser(token, null, null) }
        val small = Api(store, InMemoryHistoryStore(), verifier, clock = { 1_790_000_000_000 }, config = SMALL_CAP)
        createBand()
        fun add(i: Int) = small.handle(
            HttpRequest(
                "POST",
                "/bands/band1/ops",
                mapOf("authorization" to "Bearer alice"),
                KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("c$i", "phone", 100L + i, 0, AddCost("c$i", "m-alice", com.jacekgajek.kapelusz.model.Money(100), LocalDate(2026, 9, 1), description = "字".repeat(150)))))),
            ),
        )
        val rejected = (1..10).map { add(it) }.map { KapeluszJson.decodeFromString(PushResponse.serializer(), it.body!!) }.count { it.rejected.isNotEmpty() }
        assertTrue(rejected > 0, "150 characters each, but 450 bytes")
        val stored = store.objects.getValue(StorageKeys.band("band1")).content
        assertTrue(stored.encodeToByteArray().size < 4_000, "${stored.encodeToByteArray().size} bytes")
    }

    @Test
    fun adminsGetTheBandFileExactlyAsStored() {
        createBand()
        push("alice", AddMember("m-bob", "Bob"), CreateEvent("e", "Gig, \"Łódź\"", LocalDate(2026, 9, 13)), bandId = "band1")
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
        val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
        store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(Band.serializer(), linked))

        val raw = call("GET", "/bands/band1/raw", "alice")
        assertEquals(200, raw.status)
        assertEquals(store.objects.getValue(StorageKeys.band("band1")).content, raw.body, "byte for byte, so it can be put back")
        assertEquals(403, call("GET", "/bands/band1/raw", "bob").status, "members do not")
        assertEquals(404, call("GET", "/bands/band1/raw", "eve").status, "outsiders cannot tell the band exists")
    }

    @Test
    fun outsidersCannotSeeOrChangeTheBand() {
        createBand()
        assertEquals(404, call("GET", "/bands/band1/state", user = "mallory").status)
        assertEquals(404, push("mallory", RenameBand("Hacked"), bandId = "band1").status)
        assertEquals(404, call("GET", "/bands/nope/state").status)
    }

    @Test
    fun concurrentWriteIsRetried() {
        createBand()
        var failOnce = true
        val flaky = object : BlobStore by store {
            override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
                if (failOnce && key == StorageKeys.band("band1")) {
                    failOnce = false
                    throw PreconditionFailed()
                }
                return store.put(key, content, ifMatch, ifNoneMatch)
            }
        }
        val flakyApi = Api(flaky, InMemoryHistoryStore(), { VerifiedUser(it, null, null) })
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
        "POST",
        "/bands/band1/ops",
        user,
        KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())),
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
        assertTrue(ApiErrors.TOO_MANY_DEVICES in tooMany.body!!)
        assertEquals(200, pushAs("alice", OpEnvelope("again", "device-3", 2, 2, RenameBand("Known device"))).status)
    }

    @Test
    fun missingBandHasItsOwnErrorCode() {
        val response = call("GET", "/bands/nope/state")
        assertEquals(404, response.status)
        assertTrue(ApiErrors.BAND_NOT_FOUND in response.body!!)
        assertTrue(ApiErrors.BAND_NOT_FOUND !in call("GET", "/no/such/route").body!!)
    }

    @Test
    fun answersCorsPreflightWithoutToken() {
        assertEquals(204, call("OPTIONS", "/me", user = null).status)
        assertEquals(204, call("OPTIONS", "/bands/band1/ops", user = null).status)
    }
}

/** A cap three thousand bytes small, so a few costs reach it. */
private val SMALL_CAP = ServerConfig(maxBandBytes = 3_000)
