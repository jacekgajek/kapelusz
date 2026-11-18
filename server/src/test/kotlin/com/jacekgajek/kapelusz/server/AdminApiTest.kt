package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.ErrorResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.server.admin.AccountRow
import com.jacekgajek.kapelusz.server.admin.AdminApi
import com.jacekgajek.kapelusz.server.admin.AdminConfig
import com.jacekgajek.kapelusz.server.admin.BandDetails
import com.jacekgajek.kapelusz.server.admin.BandList
import com.jacekgajek.kapelusz.server.admin.InMemoryRequestLog
import com.jacekgajek.kapelusz.server.admin.RequestLogUnavailable
import com.jacekgajek.kapelusz.server.admin.SyncFailureList
import com.jacekgajek.kapelusz.server.auth.TokenVerifier
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.lambda.AdminHandler
import com.jacekgajek.kapelusz.server.store.Blob
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.DeletedBand
import com.jacekgajek.kapelusz.server.store.InMemoryBlobStore
import com.jacekgajek.kapelusz.server.store.StorageKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer

/**
 * The admin panel beside the app's API, on the same store. Tokens are user ids; "root" is the one admin. A token
 * that starts with "bad" is one the verifier refuses.
 */
private class AdminFixture(
    admins: Set<String> = setOf("root"),
    clientId: String? = "client-1.apps.googleusercontent.com",
    hasLog: Boolean = true,
) : ApiHarness() {
    /** The API's failed requests, as its handler would note them. */
    val log = InMemoryRequestLog({ now })
    val admin = AdminApi(
        store,
        verifier = { token -> if (token.startsWith("bad")) null else VerifiedUser(token, null, null) },
        config = AdminConfig(admins, clientId),
        clock = { now },
        requestLog = log.takeIf { hasLog },
    )

    fun admin(path: String, user: String? = "root", method: String = "GET"): HttpResponse {
        val authorization = listOfNotNull(user?.let { "authorization" to "Bearer $it" }).toMap()
        return admin.handle(HttpRequest(method, path.substringBefore('?'), authorization, query = parseQuery(path.substringAfter('?', ""))))
    }

    /** Alice's band with Bob (joined, with a phone), Carol (not linked) and one event. */
    fun setup() {
        createBand()
        pushed("alice", AddMember("m-bob", "Bob"), AddMember("m-carol", "Carol"), CreateEvent("e1", "Gig", LocalDate(2026, 10, 1)))
        join("bob", "m-bob")
        call("PUT", "/me/devices/install-1", "bob", """{"token":"fcm-secret-token-0123456789"}""").status shouldBe 200
    }

    fun stored(bandId: String = "band1") = store.get(StorageKeys.band(bandId))!!
}

class AdminApiTest :
    ExpectSpec({

        context("the page") {
            expect("is served without a sign-in, with the client id and a nonce that its policy allows") {
                val fixture = AdminFixture()

                val page = fixture.admin("/", user = null)

                page.status shouldBe 200
                page.headers["content-type"] shouldBe "text/html; charset=utf-8"
                page.body!! shouldContain """<meta name="google-client-id" content="client-1.apps.googleusercontent.com">"""
                page.body!! shouldContain """<meta name="sign-in" content="google">"""
                page.body!! shouldNotContain "{{"
                val nonce = Regex("""<script nonce="([^"]+)">""").find(page.body!!)!!.groupValues[1]
                page.headers["content-security-policy"]!! shouldContain "script-src 'nonce-$nonce' https://accounts.google.com/gsi/client"
                page.headers["content-security-policy"]!! shouldContain "frame-ancestors 'none'"
            }

            expect("has a new nonce every time") {
                val fixture = AdminFixture()

                val first = fixture.admin("/").headers["content-security-policy"]
                val second = fixture.admin("/").headers["content-security-policy"]

                (first == second) shouldBe false
            }

            expect("offers the development sign-in only without a client id at all, and none with an empty one") {
                AdminFixture(clientId = null).admin("/").body!! shouldContain """<meta name="sign-in" content="dev">"""
                AdminFixture(clientId = "").admin("/").body!! shouldContain """<meta name="sign-in" content="none">"""
            }

            expect("is refused a client id that is not shaped like one, as it goes into the page") {
                shouldThrow<IllegalArgumentException> { AdminFixture(clientId = """x"><script>""") }
            }

            expect("is the only path outside api/") {
                val fixture = AdminFixture()

                fixture.admin("/bands", user = null).status shouldBe 404
                fixture.admin("/", user = null, method = "POST").status shouldBe 404
            }
        }

        context("who gets in") {
            expect("nobody without a token") {
                val fixture = AdminFixture()
                fixture.setup()

                val answer = fixture.admin("/api/bands", user = null)

                answer.status shouldBe 401
                answer.code() shouldBe ApiErrors.UNAUTHORIZED
            }

            expect("nobody with a token the verifier refuses") {
                val fixture = AdminFixture(admins = setOf("root", "bad-root"))

                fixture.admin("/api/bands", user = "bad-root").status shouldBe 401
            }

            expect("a signed-in user who is not an admin is refused on every route, an owner of the band included") {
                val fixture = AdminFixture()
                fixture.setup()

                for (path in listOf("/api/bands", "/api/bands/band1", "/api/bands/band1/raw")) {
                    val answer = fixture.admin(path, user = "alice")
                    answer.status shouldBe 403
                    answer.code() shouldBe ApiErrors.FORBIDDEN
                    answer.body!! shouldNotContain "Night Owls"
                }
            }

            expect("nobody when no admins are set") {
                val fixture = AdminFixture(admins = emptySet())

                fixture.admin("/api/bands", user = "root").status shouldBe 403
            }

            expect("the admin, and the answer is not to be cached") {
                val fixture = AdminFixture()

                val answer = fixture.admin("/api/bands")

                answer.status shouldBe 200
                answer.headers["cache-control"] shouldBe "no-store"
            }

            expect("the admin only reads: no other method has a route") {
                val fixture = AdminFixture()
                fixture.setup()

                for (method in listOf("POST", "PUT", "DELETE")) {
                    fixture.admin("/api/bands/band1", method = method).status shouldBe 404
                    fixture.admin("/api/bands/band1/raw", method = method).status shouldBe 404
                }
            }

            expect("every answer, a refusal too, says not to cache it or guess its type, and to keep to HTTPS") {
                val fixture = AdminFixture()

                val signedIn = listOf(fixture.admin("/"), fixture.admin("/api/bands"), fixture.admin("/nope"))
                val answers = signedIn + fixture.admin("/api/bands", user = null)

                for (answer in answers) {
                    answer.headers["cache-control"] shouldBe "no-store"
                    answer.headers["x-content-type-options"] shouldBe "nosniff"
                    answer.headers["strict-transport-security"] shouldBe "max-age=31536000"
                }
            }
        }

        context("as the Lambda sets it up") {
            // A verifier that would let anyone in, to show what the setup itself refuses. It notes its audience.
            val audiences = mutableListOf<Set<String>>()
            val anyone = { clientIds: Set<String> ->
                audiences += clientIds
                TokenVerifier { token -> VerifiedUser(token, null, null) }
            }

            fun bands(api: AdminApi, user: String) = api.handle(HttpRequest("GET", "/api/bands", mapOf("authorization" to "Bearer $user")))

            expect("tokens are checked for the panel's own client only, and the listed users get in") {
                val api = AdminHandler.adminApi(InMemoryBlobStore(), " client-1 ", " root , second ,", verifier = anyone)

                audiences.last() shouldBe setOf("client-1")
                bands(api, "root").status shouldBe 200
                bands(api, "second").status shouldBe 200
                bands(api, "alice").status shouldBe 403
            }

            expect("without a client id nobody gets in, listed or not, and no verifier is made") {
                val made = audiences.size

                for (clientId in listOf(null, "", "  ")) {
                    val api = AdminHandler.adminApi(InMemoryBlobStore(), clientId, "root", verifier = anyone)
                    bands(api, "root").status shouldBe 403
                    api.handle(HttpRequest("GET", "/")).body!! shouldContain """<meta name="sign-in" content="none">"""
                }
                audiences.size shouldBe made
            }

            expect("without user ids nobody gets in") {
                for (userIds in listOf(null, "", " , ")) {
                    bands(AdminHandler.adminApi(InMemoryBlobStore(), "client-1", userIds, verifier = anyone), "root").status shouldBe 403
                }
            }
        }

        context("the band list") {
            expect("shows each band's name, members, events, size and time") {
                val fixture = AdminFixture()
                fixture.setup()
                fixture.createBand("dave", "band2", "Zebra")

                val list = fixture.admin("/api/bands").parsed(BandList.serializer(), fixture)

                list.bands.map { it.name } shouldContainExactly listOf("Night Owls", "Zebra")
                val owls = list.bands.first()
                owls.id shouldBe "band1"
                owls.members shouldBe 3
                owls.linked shouldBe 2
                owls.events shouldBe 1
                owls.bytes shouldBe fixture.stored().content.toByteArray().size.toLong()
                owls.deleteAt.shouldBeNull()
                owls.unreadable shouldBe false
                list.maxBandBytes shouldBe Api.MAX_BAND_BYTES
                list.deleted.shouldBeEmpty()
            }

            expect("says zero and empty in so many words, since the page reads the fields as they are") {
                val fixture = AdminFixture()
                fixture.createBand()

                val list = fixture.admin("/api/bands").body!!
                val band = fixture.admin("/api/bands/band1").body!!

                list shouldContain "\"events\":0"
                list shouldContain "\"unreadable\":false"
                band shouldContain "\"devices\":[]"
                band shouldContain "\"archived\":false"
                band shouldContain "\"costs\":0"
            }

            expect("still shows a band whose file is not a band, marked unreadable") {
                val fixture = AdminFixture()
                fixture.setup()

                fixture.store.put(StorageKeys.band("band1"), """{"broken": true}""")

                val row = fixture.admin("/api/bands").parsed(BandList.serializer(), fixture).bands.single()
                row.id shouldBe "band1"
                row.unreadable shouldBe true
                row.name.shouldBeNull()
            }

            expect("still shows the other bands when one band's file cannot be fetched at all") {
                val fixture = AdminFixture()
                fixture.setup()
                fixture.createBand("dave", "band2", "Zebra")
                val failing = object : BlobStore by fixture.store {
                    override fun get(key: String): Blob? {
                        check(key != StorageKeys.band("band1")) { "S3 is down for this one" }
                        return fixture.store.get(key)
                    }
                }
                val admin = AdminApi(failing, { VerifiedUser(it, null, null) }, AdminConfig(setOf("root"), "client-1"))

                val answer = admin.handle(HttpRequest("GET", "/api/bands", mapOf("authorization" to "Bearer root")))

                val rows = answer.parsed(BandList.serializer(), fixture).bands
                rows.single { it.id == "band1" }.unreadable shouldBe true
                rows.single { it.id == "band2" }.name shouldBe "Zebra"
            }

            expect("marks a band that waits to be deleted, and does not delete it when its time has come") {
                val fixture = AdminFixture()
                fixture.setup()
                val blob = fixture.stored()
                val band = KapeluszJson.decodeFromString(Band.serializer(), blob.content)
                val due = fixture.now - 1

                val waiting = band.copy(deletion = BandDeletion("m-alice", due - 1000, due))
                fixture.store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(Band.serializer(), waiting))

                fixture.admin("/api/bands").parsed(BandList.serializer(), fixture).bands.single().deleteAt shouldBe due
                fixture.admin("/api/bands/band1").parsed(BandDetails.serializer(), fixture).deletion!!.requestedBy shouldBe "Alice"
                fixture.store.get(StorageKeys.band("band1")).shouldNotBeNull()
            }

            expect("lists deleted bands apart, by what is left of them") {
                val fixture = AdminFixture()

                val left = DeletedBand("Old Band", "Alice", 123, listOf("alice"))
                fixture.store.put(StorageKeys.deleted("gone1"), KapeluszJson.encodeToString(DeletedBand.serializer(), left))

                val list = fixture.admin("/api/bands").parsed(BandList.serializer(), fixture)
                list.bands.shouldBeEmpty()
                list.deleted.single().let {
                    it.id shouldBe "gone1"
                    it.name shouldBe "Old Band"
                    it.deletedBy shouldBe "Alice"
                    it.deletedAt shouldBe 123
                }
            }
        }

        context("one band") {
            expect("shows its members with roles, accounts and devices, and its counts") {
                val fixture = AdminFixture()
                fixture.setup()

                val answer = fixture.admin("/api/bands/band1")

                val band = answer.parsed(BandDetails.serializer(), fixture)
                band.name shouldBe "Night Owls"
                band.currency shouldBe "PLN"
                band.etag shouldBe fixture.stored().etag
                band.events shouldBe 1
                band.costs shouldBe 0
                band.inviteCodes shouldBe 0
                band.members.map { it.name } shouldContainExactly listOf("Alice", "Bob", "Carol")
                band.members[0].let {
                    it.role shouldBe "OWNER"
                    it.userId shouldBe "alice"
                    it.devices.shouldBeEmpty()
                }
                band.members[1].let {
                    it.userId shouldBe "bob"
                    it.devices.single().lastSeen shouldBe fixture.now
                    it.devices.single().fresh shouldBe true
                }
                band.members[2].userId.shouldBeNull()
                // A device's token lets its holder be sent messages: it stays on the server.
                answer.body!! shouldNotContain "fcm-secret-token"
            }

            expect("counts open invite codes without showing them") {
                val fixture = AdminFixture()
                fixture.setup()

                val code = fixture.inviteCode("m-carol")

                val answer = fixture.admin("/api/bands/band1")
                answer.parsed(BandDetails.serializer(), fixture).inviteCodes shouldBe 1
                answer.body!! shouldNotContain code
            }

            expect("is not found when there is none, and unreadable when its file is not a band") {
                val fixture = AdminFixture()
                fixture.setup()

                fixture.admin("/api/bands/nope").status shouldBe 404
                fixture.admin("/api/bands/..%2Fusers").status shouldBe 400

                fixture.store.put(StorageKeys.band("band1"), "not json")

                fixture.admin("/api/bands/band1").status shouldBe 422
            }
        }

        context("a band's attachments") {
            expect("are counted, and their size is given beside the size of the band's file") {
                val fixture = AdminFixture()
                fixture.setup()

                fixture.store.put("bands/band1/attachments/a1.jpg", "x".repeat(700))
                fixture.store.put("bands/band1/attachments/a2.pdf", "y".repeat(300))
                fixture.store.put("bands/band10/attachments/other.jpg", "another band's")
                // A file a client uploaded: the store in memory keeps only its name.
                fixture.store.files += "bands/band1/attachments/a3.jpg"

                val band = fixture.admin("/api/bands/band1").parsed(BandDetails.serializer(), fixture)
                band.attachments shouldBe 3
                band.attachmentBytes shouldBe 1000
                band.bytes shouldBe fixture.stored().content.toByteArray().size.toLong()
            }

            expect("are none in so many words when there are none") {
                val fixture = AdminFixture()
                fixture.setup()

                val body = fixture.admin("/api/bands/band1").body!!

                body shouldContain "\"attachments\":0"
                body shouldContain "\"attachmentBytes\":0"
            }
        }

        context("the devices that could not sync") {
            fun AdminFixture.failures(query: String = "") = admin("/api/sync-failures$query").parsed(SyncFailureList.serializer(), this)

            expect("are the app's failed sync requests of the last days, per device") {
                val fixture = AdminFixture()
                fixture.setup()
                fixture.log.record(RequestLine("POST", "/bands/band1/ops", 409, 5, "bob", "install-1", "250", ApiErrors.BUSY))
                fixture.log.record(RequestLine("POST", "/bands/band1/ops", 200, 5, "bob", "install-1", "250"))
                fixture.log.record(RequestLine("POST", "/bands/band1/leave", 409, 5, "alice", null, "250", ApiErrors.OWNER_CANNOT_LEAVE))

                fixture.now += 2 * DAY

                fixture.log.record(RequestLine("GET", "/me", 426, 5, null, null, "11", ApiErrors.UPDATE_REQUIRED))

                val list = fixture.failures()
                list.available shouldBe true
                list.days shouldBe 3
                list.devices.single().let {
                    it.userId shouldBe "bob"
                    it.installation shouldBe "install-1"
                    it.kinds.single().error shouldBe ApiErrors.BUSY
                }
                list.unidentified!!.kinds.single().status shouldBe 426

                fixture.failures("?days=1").devices.shouldBeEmpty()
            }

            expect("go back 14 days at most, and 3 when the days make no sense") {
                val fixture = AdminFixture()
                fixture.log.record(RequestLine("GET", "/me", 500, 5, "bob", null, "250", ApiErrors.INTERNAL))

                fixture.now += 13 * DAY

                fixture.failures("?days=99").let {
                    it.days shouldBe 14
                    it.devices.size shouldBe 1
                }
                fixture.failures("?days=0").days shouldBe 1
                fixture.failures("?days=many").days shouldBe 3
            }

            expect("say so when this server keeps no request log") {
                val list = AdminFixture(hasLog = false).failures()

                list.available shouldBe false
                list.devices.shouldBeEmpty()
            }

            expect("answer 'try later' when the log cannot be read, not a server error") {
                val fixture = AdminFixture()
                val broken = AdminApi(fixture.store, { VerifiedUser(it, null, null) }, AdminConfig(setOf("root"), "client-1")) { _, _ ->
                    throw RequestLogUnavailable("The log query took too long")
                }

                val answer = broken.handle(HttpRequest("GET", "/api/sync-failures", mapOf("authorization" to "Bearer root")))

                answer.status shouldBe 503
                answer.body!! shouldContain "took too long"
            }

            expect("are for admins only") {
                val fixture = AdminFixture()

                fixture.admin("/api/sync-failures", user = "alice").status shouldBe 403
                fixture.admin("/api/sync-failures", user = null).status shouldBe 401
            }

            expect("can be named: the band list gives each band's accounts") {
                val fixture = AdminFixture()
                fixture.setup()

                val accounts = fixture.admin("/api/bands").parsed(BandList.serializer(), fixture).bands.single().accounts

                accounts shouldContainExactly listOf(AccountRow("alice", "Alice"), AccountRow("bob", "Bob"))
            }
        }

        context("the raw data") {
            expect("is the band's file exactly as stored, with its ETag, also when it is not a band") {
                val fixture = AdminFixture()
                fixture.setup()

                val raw = fixture.admin("/api/bands/band1/raw")

                raw.status shouldBe 200
                raw.body shouldBe fixture.stored().content
                raw.headers["etag"] shouldBe fixture.stored().etag
                raw.headers["cache-control"] shouldBe "no-store"

                fixture.store.put(StorageKeys.band("band1"), "not json")

                fixture.admin("/api/bands/band1/raw").body shouldBe "not json"
                fixture.admin("/api/bands/nope/raw").status shouldBe 404
            }
        }

        context("reading changes nothing") {
            expect("the store is the same after every route was read") {
                val fixture = AdminFixture()
                fixture.setup()
                val before = fixture.store.objects.toMap()

                val routes = listOf("/", "/api/bands", "/api/bands/band1", "/api/bands/band1/raw", "/api/sync-failures")
                routes.forEach { fixture.admin(it).status shouldBe 200 }

                fixture.store.objects.toMap() shouldBe before
            }
        }
    })

private const val DAY = 24 * 3600 * 1000L

/** The error code of a refusal. */
private fun HttpResponse.code(): String = KapeluszJson.decodeFromString(ErrorResponse.serializer(), body!!).error

/** The body of a 200 answer, decoded. */
private fun <T> HttpResponse.parsed(serializer: KSerializer<T>, fixture: AdminFixture): T = with(fixture) { parse(serializer) }
