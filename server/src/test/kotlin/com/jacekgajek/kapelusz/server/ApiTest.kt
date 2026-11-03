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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate

/** The harness of [ApiTest]: users are signed in with an e-mail and a name made of their token. */
private class ApiTestApi : ApiHarness(verifier = { token -> VerifiedUser(token, "$token@example.com", token) }) {
    fun pushAs(user: String, vararg envelopes: OpEnvelope) = call(
        "POST",
        "/bands/band1/ops",
        user,
        KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())),
    )
}

class ApiTest :
    ExpectSpec({
        expect("requires valid token") {
            with(ApiTestApi()) {
                call("GET", "/me", user = null).status shouldBe 401
                val rejecting = Api(store, InMemoryHistoryStore(), { null })
                rejecting.handle(HttpRequest("GET", "/me", mapOf("authorization" to "Bearer x"))).status shouldBe 401
            }
        }

        expect("event links are not found without the web page") {
            with(ApiTestApi()) {
                // CloudFront then shows the web app's page, which it cannot do for a 401.
                call("GET", "/e/0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000", user = null).status shouldBe 404
            }
        }

        expect("create band then list and fetch state") {
            with(ApiTestApi()) {
                val created = createBand().parse(BandStateResponse.serializer())
                created.band.owner?.userId shouldBe "alice"
                created.band.member("m-alice")?.role shouldBe Role.OWNER

                val me = call("GET", "/me").parse(MeResponse.serializer())
                me.bands.map { it.id } shouldBe listOf("band1")
                me.bands.single().memberName shouldBe "Alice"

                val state = call("GET", "/bands/band1/state").parse(BandStateResponse.serializer())
                state.version shouldBe created.version
                call("GET", "/bands/band1/state", headers = mapOf("if-none-match" to state.version)).status shouldBe 304
            }
        }

        expect("retried create is idempotent but foreign id conflicts") {
            with(ApiTestApi()) {
                createBand().status shouldBe 200
                withClue("same owner retrying") { createBand().status shouldBe 200 }
                createBand(user = "mallory").status shouldBe 409
            }
        }

        expect("push applies ops idempotently and keeps history") {
            with(ApiTestApi()) {
                createBand()
                val request = KapeluszJson.encodeToString(
                    PushRequest.serializer(),
                    PushRequest(
                        listOf(
                            OpEnvelope("a", "phone", 1, 1, RenameBand("Renamed")),
                            OpEnvelope("b", "phone", 2, 2, AddMember("bob", "Bob")),
                        ),
                    ),
                )
                val first = call("POST", "/bands/band1/ops", body = request).parse(PushResponse.serializer())
                first.accepted shouldBe listOf("a", "b")
                first.band.name shouldBe "Renamed"

                val retry = call("POST", "/bands/band1/ops", body = request).parse(PushResponse.serializer())
                retry.duplicates shouldBe listOf("a", "b")
                retry.band.members.size shouldBe 2
                withClue("sequence numbers are per user and device") {
                    retry.band.lastSeq.keys.forAll { it shouldStartWith "alice/" }
                }

                // One entry for the push (a record per operation); the retried duplicates add nothing.
                val records = history.bands.getValue("band1").keys
                records.size shouldBe 2
                records.map { it.substringBefore('#') }.distinct().count { it.startsWith("2026-09/") } shouldBe 1
            }
        }

        expect("permissions are enforced on the server") {
            with(ApiTestApi()) {
                createBand()
                // Bob joins as a plain member, as if he accepted an invite (a server-internal operation).
                push("alice", AddMember("m-bob", "Bob"), bandId = "band1")
                val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
                store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(Band.serializer(), linked))

                val response =
                    push("bob", CreateEvent("e", "Gig", LocalDate(2026, 9, 13)), bandId = "band1")
                        .parse(PushResponse.serializer())
                response.rejected.values.single().reason shouldBe RejectionReason.ROLE_REQUIRED
                response.band.events.shouldBeEmpty()
            }
        }

        expect("the Google photo reaches the user's members at sign-in") {
            with(ApiTestApi()) {
                val photo = "https://lh3.googleusercontent.com/a/alice-photo=s96-c"
                // Signing in with Google brings the photo; the test verifier stands in for Google.
                val google = Api(
                    store,
                    InMemoryHistoryStore(),
                    { token ->
                        VerifiedUser(token.removeSuffix("-google"), null, null, picture = photo.takeIf { token.endsWith("-google") })
                    },
                    clock = { 1_790_000_000_000 },
                )
                createBand()
                google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer alice-google"))).status shouldBe 200
                val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                band.memberOfUser("alice")?.pictureUrl shouldBe photo

                // A band created afterwards gets it too.
                createBand(bandId = "band2")
                KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band2")).content)
                    .memberOfUser("alice")?.pictureUrl shouldBe photo

                // Anything but a Google photo address is ignored.
                val other = Api(
                    store,
                    InMemoryHistoryStore(),
                    { token -> VerifiedUser(token, null, null, picture = "https://evil.example/x.png") },
                    clock = { 1_790_000_000_000 },
                )
                other.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer alice")))
                KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                    .memberOfUser("alice")?.pictureUrl shouldBe photo
            }
        }

        expect("an already signed-in user sends their kept Google token for the photo") {
            with(ApiTestApi()) {
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
                withClue("someone else's token") { send("alice", "old-google-token-of-bob").status shouldBe 400 }
                send("alice", "old-google-token-of-alice").status shouldBe 204
                KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                    .memberOfUser("alice")?.pictureUrl shouldBe photo
            }
        }

        expect("outsiders cannot make the server read the log and undos are bounded") {
            with(ApiTestApi()) {
                createBand()
                fun undos(user: String, n: Int) = call(
                    "POST",
                    "/bands/band1/ops",
                    user,
                    KapeluszJson.encodeToString(
                        PushRequest.serializer(),
                        PushRequest(
                            (1..n).map {
                                OpEnvelope(
                                    "u$it",
                                    "phone",
                                    900L + it,
                                    0,
                                    Undo(
                                        "x$it",
                                        RenameBand("x"),
                                        BandChange(BandPart(), BandPart()),
                                        "2026-01/1-00000000-0000-0000-0000-000000000000.json",
                                    ),
                                )
                            },
                        ),
                    ),
                )
                withClue("not a member: refused before any undo lookup") { undos("mallory", 5).status shouldBe 404 }
                undos("alice", Api.MAX_UNDOS_PER_PUSH + 1).status shouldBe 400
            }
        }

        expect("one account may be in at most 20 bands") {
            with(ApiTestApi()) {
                repeat(Api.MAX_BANDS_PER_USER) { createBand(bandId = "b$it").status shouldBe 200 }
                createBand(bandId = "one-more").status shouldBe 409
            }
        }

        // Only bands the account is still in count; an archived member is not shown the band (R9).
        expect("bands the account left do not count towards the limit") {
            with(ApiTestApi()) {
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
                call("POST", "/invites/${invite.code}/accept", "bob").status shouldBe 200
                repeat(Api.MAX_BANDS_PER_USER - 1) { createBand("bob", "bob$it").status shouldBe 200 }
                createBand("bob", "one-more").status shouldBe 409

                push("alice", com.jacekgajek.kapelusz.ops.SetMemberArchived("m-bob", true), bandId = "shared")
                withClue("archived: not listed") {
                    call("GET", "/me", "bob")
                        .parse(com.jacekgajek.kapelusz.api.MeResponse.serializer())
                        .bands.none { it.id == "shared" } shouldBe true
                }
                withClue("archived members can be restored: the band still counts") { createBand("bob", "one-more").status shouldBe 409 }

                val restored = com.jacekgajek.kapelusz.ops.SetMemberArchived("m-bob", false)
                push("alice", restored, com.jacekgajek.kapelusz.ops.UnlinkUser("m-bob"), bandId = "shared")
                withClue("unlinked: the band no longer counts") { createBand("bob", "one-more").status shouldBe 200 }
            }
        }

        expect("band creation keeps to the limits") {
            with(ApiTestApi()) {
                fun create(bandId: String, ownerMemberId: String, name: String = "Night Owls") = call(
                    "POST",
                    "/bands",
                    "alice",
                    KapeluszJson.encodeToString(
                        CreateBandRequest.serializer(),
                        CreateBandRequest(bandId, name, "PLN", ownerMemberId, "Alice"),
                    ),
                )
                create("../evil", "m-alice").status shouldBe 400
                create("band2", "band-fund").status shouldBe 400
                create("band3", "m-alice", name = "x".repeat(200)).status shouldBe 400
                create("band4", "m-alice").status shouldBe 200
            }
        }

        expect("a push that would make the band too large to load is refused") {
            with(ApiTestApi()) {
                val verifier = TokenVerifier { token -> VerifiedUser(token, null, null) }
                val small = Api(store, InMemoryHistoryStore(), verifier, clock = { 1_790_000_000_000 }, config = SMALL_CAP)
                createBand()
                fun add(i: Int) = small.handle(
                    HttpRequest(
                        "POST",
                        "/bands/band1/ops",
                        mapOf("authorization" to "Bearer alice"),
                        KapeluszJson.encodeToString(
                            PushRequest.serializer(),
                            PushRequest(
                                listOf(
                                    OpEnvelope(
                                        "c$i",
                                        "phone",
                                        100L + i,
                                        0,
                                        AddCost(
                                            "c$i",
                                            "m-alice",
                                            com.jacekgajek.kapelusz.model.Money(100),
                                            LocalDate(2026, 9, 1),
                                            description = "d".repeat(400),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                val responses = (1..10).map { add(it) }
                withClue("the push goes through; only the operation is refused") { responses.forAll { it.status shouldBe 200 } }
                val last = KapeluszJson.decodeFromString(PushResponse.serializer(), responses.last().body!!)
                last.rejected.values.single().reason shouldBe RejectionReason.LIMIT_REACHED
                // Refused operations are remembered (for retried pushes, at most 20), so the file may pass the cap by that.
                val stored = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                withClue("the operation that would pass the cap was not applied") { stored.cost("c10").shouldBeNull() }
                store.objects.getValue(StorageKeys.band("band1")).content.length shouldBeLessThan 4_000
            }
        }

        // The cap counts bytes; text in other scripts takes up to 3 bytes a character.
        expect("the size cap counts bytes") {
            with(ApiTestApi()) {
                val verifier = TokenVerifier { token -> VerifiedUser(token, null, null) }
                val small = Api(store, InMemoryHistoryStore(), verifier, clock = { 1_790_000_000_000 }, config = SMALL_CAP)
                createBand()
                fun add(i: Int) = small.handle(
                    HttpRequest(
                        "POST",
                        "/bands/band1/ops",
                        mapOf("authorization" to "Bearer alice"),
                        KapeluszJson.encodeToString(
                            PushRequest.serializer(),
                            PushRequest(
                                listOf(
                                    OpEnvelope(
                                        "c$i",
                                        "phone",
                                        100L + i,
                                        0,
                                        AddCost(
                                            "c$i",
                                            "m-alice",
                                            com.jacekgajek.kapelusz.model.Money(100),
                                            LocalDate(2026, 9, 1),
                                            description = "字".repeat(150),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                val rejected = (1..10).map { add(it) }
                    .map { KapeluszJson.decodeFromString(PushResponse.serializer(), it.body!!) }
                    .count { it.rejected.isNotEmpty() }
                withClue("150 characters each, but 450 bytes") { rejected shouldBeGreaterThan 0 }
                val stored = store.objects.getValue(StorageKeys.band("band1")).content
                withClue("${stored.encodeToByteArray().size} bytes") { stored.encodeToByteArray().size shouldBeLessThan 4_000 }
            }
        }

        expect("admins get the band file exactly as stored") {
            with(ApiTestApi()) {
                createBand()
                push("alice", AddMember("m-bob", "Bob"), CreateEvent("e", "Gig, \"Łódź\"", LocalDate(2026, 9, 13)), bandId = "band1")
                val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                val linked = (Engine.apply(band, Actor.System, LinkUser("m-bob", "bob", Role.MEMBER), "x", 0) as ApplyResult.Applied).band
                store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(Band.serializer(), linked))

                val raw = call("GET", "/bands/band1/raw", "alice")
                raw.status shouldBe 200
                withClue("byte for byte, so it can be put back") {
                    raw.body shouldBe store.objects.getValue(StorageKeys.band("band1")).content
                }
                withClue("members do not") { call("GET", "/bands/band1/raw", "bob").status shouldBe 403 }
                withClue("outsiders cannot tell the band exists") { call("GET", "/bands/band1/raw", "eve").status shouldBe 404 }
            }
        }

        expect("outsiders cannot see or change the band") {
            with(ApiTestApi()) {
                createBand()
                call("GET", "/bands/band1/state", user = "mallory").status shouldBe 404
                push("mallory", RenameBand("Hacked"), bandId = "band1").status shouldBe 404
                call("GET", "/bands/nope/state").status shouldBe 404
            }
        }

        expect("concurrent write is retried") {
            with(ApiTestApi()) {
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
                response.parse(PushResponse.serializer()).accepted shouldBe listOf("a")
            }
        }

        expect("rejects bad input") {
            with(ApiTestApi()) {
                call("POST", "/bands", body = "{ nope").status shouldBe 400
                createBand(bandId = "../etc").status shouldBe 400
                call("GET", "/nothing").status shouldBe 404
            }
        }

        expect("device ids are bounded so nobody can bloat the band") {
            with(ApiTestApi()) {
                createBand()
                val long = "x".repeat(65)
                pushAs("alice", OpEnvelope("a", long, 1, 1, RenameBand("X"))).status shouldBe 400
                pushAs(
                    "alice",
                    OpEnvelope("a", "phone", 1, 1, RenameBand("X")),
                    OpEnvelope("b", "tablet", 1, 1, RenameBand("Y")),
                ).status shouldBe 400

                repeat(Api.MAX_DEVICES_PER_USER) { i ->
                    pushAs("alice", OpEnvelope("op$i", "device-$i", 1, 1, RenameBand("N$i"))).status shouldBe 200
                }
                val tooMany = pushAs("alice", OpEnvelope("over", "device-new", 1, 1, RenameBand("Z")))
                tooMany.status shouldBe 400
                tooMany.body!! shouldContain ApiErrors.TOO_MANY_DEVICES
                pushAs("alice", OpEnvelope("again", "device-3", 2, 2, RenameBand("Known device"))).status shouldBe 200
            }
        }

        expect("missing band has its own error code") {
            with(ApiTestApi()) {
                val response = call("GET", "/bands/nope/state")
                response.status shouldBe 404
                response.body!! shouldContain ApiErrors.BAND_NOT_FOUND
                call("GET", "/no/such/route").body!! shouldNotContain ApiErrors.BAND_NOT_FOUND
            }
        }

        expect("answers CORS preflight without token") {
            with(ApiTestApi()) {
                call("OPTIONS", "/me", user = null).status shouldBe 204
                call("OPTIONS", "/bands/band1/ops", user = null).status shouldBe 204
            }
        }
    })

/** A cap three thousand bytes small, so a few costs reach it. */
private val SMALL_CAP = ServerConfig(maxBandBytes = 3_000)
