package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.HistoryResponse
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.RestoreBand
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.server.admin.AdminApi
import com.jacekgajek.kapelusz.server.admin.AdminConfig
import com.jacekgajek.kapelusz.server.admin.RestoreDone
import com.jacekgajek.kapelusz.server.admin.RestorePreview
import com.jacekgajek.kapelusz.server.admin.RestoreRequest
import com.jacekgajek.kapelusz.server.admin.VersionList
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.PreconditionFailed
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.UserRecord
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer

private const val MINUTE = 60 * 1000L
private const val HOUR = 60 * MINUTE

/**
 * The admin panel beside the app's API, on the same store and history. "root" is the admin.
 *
 * [story] makes a band with a past worth going back to. Then: Alice owns it; Bob joined; there is a gig. An hour
 * later ("now"): the band is renamed, the gig is deleted, Bob's account is unlinked, and Dave was added and joined.
 */
private class RestoreFixture(hasHistory: Boolean = true) : ApiHarness() {
    val admin = AdminApi(
        store,
        verifier = { token -> VerifiedUser(token, null, null) },
        config = AdminConfig(setOf("root"), "client-1"),
        clock = { now },
        historyStore = history.takeIf { hasHistory },
    )

    /** The copy of the band's file to go back to: the last one written "then". */
    lateinit var then: String

    /** When that copy was written. */
    var thenAt = 0L

    fun story() {
        createBand()
        pushed("alice", AddMember("m-bob", "Bob"), CreateEvent("e1", "Gig", LocalDate(2026, 10, 1)))
        join("bob", "m-bob")
        thenAt = now
        then = versions().versions.first().id

        now += HOUR
        pushed("alice", RenameBand("Day Owls"), DeleteEvent("e1"), UnlinkUser("m-bob"), AddMember("m-dave", "Dave"))
        join("dave", "m-dave")

        // The admin looks a minute later. History entries of the same millisecond have no order.
        now += MINUTE
    }

    /** The sign-in header of [user]; none for null. */
    private fun signedIn(user: String?) = listOfNotNull(user?.let { "authorization" to "Bearer $it" }).toMap()

    fun get(path: String, query: Map<String, String> = emptyMap(), user: String? = "root"): HttpResponse {
        val request = HttpRequest("GET", path, signedIn(user), query = query)
        return admin.handle(request)
    }

    fun <T> read(serializer: KSerializer<T>, path: String, query: Map<String, String> = emptyMap()): T = get(path, query).parse(serializer)

    fun versions(bandId: String = "band1") = read(VersionList.serializer(), "/api/bands/$bandId/versions")

    fun preview(versionId: String = then) = read(RestorePreview.serializer(), "/api/bands/band1/restore", mapOf("version" to versionId))

    /** Asks for the restore the way the page does: with what the preview gave, and the name typed. */
    fun restore(versionId: String = then, name: String? = null, etag: String? = null, user: String? = "root"): HttpResponse {
        val preview = preview(versionId)
        val request = RestoreRequest(versionId, etag ?: preview.currentEtag, name ?: preview.name)
        val body = KapeluszJson.encodeToString(RestoreRequest.serializer(), request)
        return admin.handle(HttpRequest("POST", "/api/bands/band1/restore", signedIn(user), body))
    }

    fun band() = KapeluszJson.decodeFromString(Band.serializer(), store.get(StorageKeys.band("band1"))!!.content)

    /** The band list in the user's stored record. `GET /me` shows less: only bands the user can open. */
    fun storedBandsOf(user: String): List<String> {
        val record = store.get(StorageKeys.user(user))!!.content
        return KapeluszJson.decodeFromString(UserRecord.serializer(), record).bandIds
    }

    /** The body of a restore request for the copy "then", as the page would send it now. */
    fun restoreBody(): String {
        val preview = preview()
        return KapeluszJson.encodeToString(RestoreRequest.serializer(), RestoreRequest(then, preview.currentEtag, preview.name))
    }

    fun bandsOf(user: String) = call("GET", "/me", user).parse(MeResponse.serializer()).bands.map { it.id }

    fun historyOps(): List<Operation> {
        val entries = call("GET", "/bands/band1/log", "alice").parse(HistoryResponse.serializer()).entries
        return entries.flatMap { entry -> entry.ops.map { it.op } }
    }
}

class AdminRestoreTest :
    ExpectSpec({

        context("the copies of a band's file") {
            expect("are listed newest first, the one stored now marked") {
                val fixture = RestoreFixture()
                fixture.story()

                val list = fixture.versions()

                list.versions.first().latest shouldBe true
                list.versions.drop(1).none { it.latest } shouldBe true
                list.versions.map { it.modified } shouldBe list.versions.map { it.modified }.sortedDescending()
                list.versions.map { it.id }.contains(fixture.then) shouldBe true
                list.more shouldBe false
                list.restores.shouldBeEmpty()
            }

            expect("are not there for a band that is not, and are for admins only") {
                val fixture = RestoreFixture()
                fixture.story()

                fixture.get("/api/bands/nope/versions").status shouldBe 404
                fixture.get("/api/bands/band1/versions", user = "alice").status shouldBe 403
                fixture.get("/api/bands/band1/restore", mapOf("version" to fixture.then), user = "alice").status shouldBe 403
            }
        }

        context("what a restore would do") {
            expect("is told before it is done: what comes back, what goes, and whose account is in or out") {
                val fixture = RestoreFixture()
                fixture.story()
                val before = fixture.store.objects.toMap()

                val preview = fixture.preview()

                preview.stateOf shouldBe fixture.thenAt
                preview.name shouldBe "Day Owls"
                preview.currentReadable shouldBe true
                preview.latest shouldBe false
                preview.currentEtag shouldBe fixture.store.get(StorageKeys.band("band1"))!!.etag
                preview.events.backNames shouldContainExactly listOf("Gig")
                preview.events.gone shouldBe 0
                preview.members.goneNames shouldContainExactly listOf("Dave")
                preview.members.back shouldBe 0
                // Bob is in both, but as he was then: with his account.
                preview.members.changed shouldBe 1
                preview.settingsChange shouldBe true
                preview.gainAccess shouldContainExactly listOf("Bob")
                preview.loseAccess shouldContainExactly listOf("Dave")
                fixture.store.objects.toMap() shouldBe before
            }

            expect("is not told for a copy that is not there") {
                val fixture = RestoreFixture()
                fixture.story()

                fixture.get("/api/bands/band1/restore", mapOf("version" to "v999")).status shouldBe 404
                fixture.get("/api/bands/band1/restore").status shouldBe 400
            }
        }

        context("a restore") {
            expect("puts the band's content back, and keeps the sync bookkeeping of now") {
                val fixture = RestoreFixture()
                fixture.story()
                val seqNow = fixture.band().lastSeq

                val done = fixture.restore().parsed(RestoreDone.serializer(), fixture)

                done.problems.shouldBeEmpty()
                fixture.band().let {
                    it.name shouldBe "Night Owls"
                    it.events.map { event -> event.name } shouldContainExactly listOf("Gig")
                    it.members.map { member -> member.name } shouldContainExactly listOf("Alice", "Bob")
                    it.memberOfUser("bob")!!.role shouldBe Role.MEMBER
                    it.lastSeq shouldBe seqNow
                }
            }

            expect("does not let a phone's old push be applied again") {
                val fixture = RestoreFixture()
                fixture.story()
                fixture.restore().status shouldBe 200

                // The rename, sent again with its old number, as a phone does whose answer was lost.
                val again = PushRequest(listOf(OpEnvelope("op3", "phone", 3, 3, RenameBand("Day Owls"))))
                val body = KapeluszJson.encodeToString(PushRequest.serializer(), again)
                fixture.call("POST", "/bands/band1/ops", "alice", body).status shouldBe 200

                fixture.band().name shouldBe "Night Owls"
            }

            expect("brings the members' band lists in line: who is in again has the band, who is out has not") {
                val fixture = RestoreFixture()
                fixture.story()
                fixture.bandsOf("bob").shouldBeEmpty()
                fixture.bandsOf("dave") shouldContainExactly listOf("band1")

                fixture.restore().status shouldBe 200

                fixture.bandsOf("bob") shouldContainExactly listOf("band1")
                fixture.bandsOf("dave").shouldBeEmpty()
                fixture.call("GET", "/bands/band1/state", "bob").status shouldBe 200
                fixture.call("GET", "/bands/band1/state", "dave").status shouldBe 404
            }

            expect("points the links of events that are back to the band again") {
                val fixture = RestoreFixture()
                fixture.story()
                fixture.store.get(StorageKeys.eventPointer("e1")).shouldBeNull()

                fixture.restore().status shouldBe 200

                fixture.store.get(StorageKeys.eventPointer("e1")).shouldNotBeNull().content shouldContain "band1"
            }

            expect("leaves an entry in the band's history that the members see, by nobody of the band") {
                val fixture = RestoreFixture()
                fixture.story()

                fixture.restore().status shouldBe 200

                fixture.historyOps().first() shouldBe RestoreBand(fixture.thenAt)
                val entry = fixture.call("GET", "/bands/band1/log", "alice").parsed(HistoryResponse.serializer(), fixture).entries.first()
                entry.userId shouldBe "support"
                entry.memberId.shouldBeNull()
                entry.changes.isEmpty() shouldBe true
            }

            expect("is recorded, with the copy it replaced, so that one can be put back too") {
                val fixture = RestoreFixture()
                fixture.story()
                val replaced = fixture.versions().versions.first().id

                fixture.now += HOUR
                fixture.restore().status shouldBe 200

                val list = fixture.versions()
                list.restores.single().let {
                    it.at shouldBe fixture.now
                    it.stateOf shouldBe fixture.thenAt
                    it.versionId shouldBe fixture.then
                    it.replacedVersionId shouldBe replaced
                    it.problems.shouldBeEmpty()
                }

                // And back again: the restore itself is undone by restoring what it replaced.
                fixture.restore(replaced, name = "Night Owls").status shouldBe 200

                fixture.band().name shouldBe "Day Owls"
                fixture.band().members.map { it.name } shouldContainExactly listOf("Alice", "Bob", "Dave")
                fixture.bandsOf("dave") shouldContainExactly listOf("band1")
            }

            expect("does not bring a past deletion back, nor take a pending one away") {
                val fixture = RestoreFixture()
                fixture.story()
                val pending = BandDeletion("m-alice", fixture.now, fixture.now + BandDeletion.DELAY_MS)
                val blob = fixture.store.get(StorageKeys.band("band1"))!!
                val waiting = KapeluszJson.encodeToString(Band.serializer(), fixture.band().copy(deletion = pending))
                fixture.store.put(StorageKeys.band("band1"), waiting, blob.etag)

                fixture.restore().status shouldBe 200

                fixture.band().deletion shouldBe pending
                fixture.band().name shouldBe "Night Owls"
            }
        }

        context("archived members") {
            expect("count for access as the API counts them: archived means out, though the account stays linked") {
                val fixture = RestoreFixture()
                fixture.createBand()
                fixture.pushed("alice", AddMember("m-bob", "Bob"), AddMember("m-carol", "Carol"))
                fixture.join("bob", "m-bob")
                fixture.join("carol", "m-carol")
                fixture.pushed("alice", SetMemberArchived("m-carol", true))
                val then = fixture.versions().versions.first().id

                // Since then: Bob was archived, Carol was brought back.
                fixture.now += HOUR
                fixture.pushed("alice", SetMemberArchived("m-bob", true), SetMemberArchived("m-carol", false))
                fixture.now += MINUTE

                val preview = fixture.preview(then)

                preview.gainAccess shouldContainExactly listOf("Bob")
                preview.loseAccess shouldContainExactly listOf("Carol")

                fixture.restore(then).status shouldBe 200

                fixture.call("GET", "/bands/band1/state", "bob").status shouldBe 200
                fixture.call("GET", "/bands/band1/state", "carol").status shouldBe 404
                // Both stay linked, so both keep the band on their stored list.
                fixture.storedBandsOf("bob") shouldContainExactly listOf("band1")
                fixture.storedBandsOf("carol") shouldContainExactly listOf("band1")
            }
        }

        context("what a restore cannot bring back") {
            expect("is said before: attachments whose file was deleted, and a deletion that stays") {
                val fixture = RestoreFixture()
                fixture.story()
                val pending = BandDeletion("m-alice", fixture.now, fixture.now + BandDeletion.DELAY_MS)
                val blob = fixture.store.get(StorageKeys.band("band1"))!!
                val waiting = KapeluszJson.encodeToString(Band.serializer(), fixture.band().copy(deletion = pending))
                fixture.store.put(StorageKeys.band("band1"), waiting, blob.etag)

                val preview = fixture.preview()

                preview.missingFiles shouldBe 0
                preview.deleteAt shouldBe pending.deleteAt
                fixture.get("/api/bands/band1/restore", mapOf("version" to fixture.then)).body!! shouldContain "\"missingFiles\":0"
            }
        }

        context("a restore is refused") {
            expect("when the band changes at the very moment of the write") {
                val fixture = RestoreFixture()
                fixture.story()
                val contested = object : BlobStore by fixture.store {
                    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
                        if (key == StorageKeys.band("band1")) throw PreconditionFailed()
                        return fixture.store.put(key, content, ifMatch, ifNoneMatch)
                    }
                }
                val admin = AdminApi(contested, { VerifiedUser(it, null, null) }, AdminConfig(setOf("root"), "client-1"), { fixture.now })
                val body = fixture.restoreBody()

                val answer = admin.handle(HttpRequest("POST", "/api/bands/band1/restore", mapOf("authorization" to "Bearer root"), body))

                answer.status shouldBe 409
                answer.body!! shouldContain "changed just now"
                fixture.band().name shouldBe "Day Owls"
                fixture.versions().restores.shouldBeEmpty()
            }

            expect("unless the band's name is typed") {
                val fixture = RestoreFixture()
                fixture.story()

                fixture.restore(name = "Night Owls").let {
                    it.status shouldBe 409
                    it.body!! shouldContain "name typed"
                }
                fixture.restore(name = "").status shouldBe 409
                fixture.band().name shouldBe "Day Owls"
            }

            expect("when the band changed since the admin looked") {
                val fixture = RestoreFixture()
                fixture.story()
                val seen = fixture.preview().currentEtag

                fixture.pushed("alice", AddMember("m-eve", "Eve"))

                fixture.restore(etag = seen).let {
                    it.status shouldBe 409
                    it.body!! shouldContain "changed since you looked"
                }
                fixture.band().members.map { it.name }.contains("Eve") shouldBe true
            }

            expect("for the copy that is stored now, and for anyone but an admin") {
                val fixture = RestoreFixture()
                fixture.story()
                val latest = fixture.versions().versions.first().id

                fixture.restore(latest).status shouldBe 409
                fixture.restore(user = "alice").status shouldBe 403
                fixture.restore(user = null).status shouldBe 401

                fixture.band().name shouldBe "Day Owls"
            }
        }

        context("when what is stored now cannot be read as a band") {
            expect("the copy is taken whole, and the name to type is the copy's") {
                val fixture = RestoreFixture()
                fixture.story()
                fixture.store.put(StorageKeys.band("band1"), """{"broken": true}""")

                val preview = fixture.preview()
                val done = fixture.restore().parsed(RestoreDone.serializer(), fixture)

                preview.currentReadable shouldBe false
                preview.name shouldBe "Night Owls"
                done.problems.shouldBeEmpty()
                fixture.band().name shouldBe "Night Owls"
                fixture.band().deletion.shouldBeNull()
                fixture.bandsOf("bob") shouldContainExactly listOf("band1")
            }

            expect("the same when what is stored is not JSON at all: cut off, or plain rubbish") {
                for (rubbish in listOf("", "not json", """{"id":"band1","name":"Day Ow""")) {
                    val fixture = RestoreFixture()
                    fixture.story()
                    fixture.store.put(StorageKeys.band("band1"), rubbish)

                    // The app cannot open the band, and the panel still lists it.
                    fixture.call("GET", "/bands/band1/state", "alice").status shouldNotBe 200
                    fixture.get("/api/bands").body!! shouldContain "\"unreadable\":true"
                    fixture.get("/api/bands/band1").status shouldBe 422

                    fixture.restore().status shouldBe 200

                    fixture.band().name shouldBe "Night Owls"
                    fixture.call("GET", "/bands/band1/state", "alice").status shouldBe 200
                }
            }

            expect("a copy that cannot be read either is not offered as one") {
                val fixture = RestoreFixture()
                fixture.story()
                fixture.store.put(StorageKeys.band("band1"), "not json")
                val broken = fixture.versions().versions.first().id
                fixture.store.put(StorageKeys.band("band1"), """{"also": "broken"}""")

                fixture.get("/api/bands/band1/restore", mapOf("version" to broken)).status shouldBe 422
            }
        }

        context("when one member's band list cannot be written") {
            expect("the other members' lists are still updated, and the failure is said") {
                val fixture = RestoreFixture()
                fixture.story()
                // Since the copy, two members joined. Eve's list cannot be written; Dave's can.
                fixture.pushed("alice", AddMember("m-eve", "Eve"))
                fixture.join("eve", "m-eve")
                fixture.now += MINUTE
                val broken = object : BlobStore by fixture.store {
                    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
                        check(key != StorageKeys.user("eve")) { "S3 is down for this one" }
                        return fixture.store.put(key, content, ifMatch, ifNoneMatch)
                    }
                }
                val config = AdminConfig(setOf("root"), "client-1")
                val admin = AdminApi(broken, { VerifiedUser(it, null, null) }, config, { fixture.now }, null, fixture.history)
                val body = fixture.restoreBody()

                val answer = admin.handle(HttpRequest("POST", "/api/bands/band1/restore", mapOf("authorization" to "Bearer root"), body))

                answer.parsed(RestoreDone.serializer(), fixture).problems shouldContainExactly
                    listOf("the band lists of members who are no longer in the band")
                fixture.storedBandsOf("dave").shouldBeEmpty()
                fixture.storedBandsOf("eve") shouldContainExactly listOf("band1")
            }
        }

        context("when something beside the band cannot be written") {
            expect("the band is put back all the same, and what is missing is said and recorded") {
                val fixture = RestoreFixture(hasHistory = false)
                fixture.story()

                val done = fixture.restore().parsed(RestoreDone.serializer(), fixture)

                done.problems shouldContainExactly listOf("the entry in the band's history")
                fixture.band().name shouldBe "Night Owls"
                fixture.versions().restores.single().problems shouldContainExactly listOf("the entry in the band's history")
            }
        }

        context("a restore is support's alone") {
            expect("the app cannot push one: the rules refuse it for the owner too") {
                val fixture = RestoreFixture()
                fixture.story()

                val answer = fixture.pushed("alice", RestoreBand(fixture.thenAt))

                answer.accepted.shouldBeEmpty()
                answer.rejected.size shouldBe 1
                fixture.band().name shouldBe "Day Owls"
            }
        }
    })

/** The body of a 200 answer, decoded. */
private fun <T> HttpResponse.parsed(serializer: KSerializer<T>, fixture: RestoreFixture): T = with(fixture) { parse(serializer) }
