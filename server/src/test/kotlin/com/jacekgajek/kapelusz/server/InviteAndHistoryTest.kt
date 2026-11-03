package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.HistoryResponse
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.InvitePreview
import com.jacekgajek.kapelusz.api.InvitesResponse
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.server.auth.VerifiedUser
import com.jacekgajek.kapelusz.server.store.BlobStore
import com.jacekgajek.kapelusz.server.store.InMemoryHistoryStore
import com.jacekgajek.kapelusz.server.store.InviteCodes
import com.jacekgajek.kapelusz.server.store.JoinAttempts
import com.jacekgajek.kapelusz.server.store.StorageKeys
import com.jacekgajek.kapelusz.server.store.StoredInvite
import com.jacekgajek.kapelusz.server.store.joinAttempts
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch

private class InviteApi : ApiHarness() {
    /** Alice owns the band; Bob is an unlinked roster member. */
    fun setup() {
        createBand()
        push("alice", AddMember("m-bob", "Bob"))
    }
}

class InviteAndHistoryTest :
    ExpectSpec({
        expect("invite lifecycle") {
            with(InviteApi()) {
                setup()
                val info = invite().parse(InviteInfo.serializer())
                info.code shouldMatch Regex("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}")
                call("GET", "/bands/band1/invites", "alice")
                    .parse(InvitesResponse.serializer())
                    .invites.map { it.code } shouldBe listOf(info.code)

                val preview = call("GET", "/invites/${info.code.lowercase()}", "bob").parse(InvitePreview.serializer())
                preview.bandName shouldBe "Night Owls"
                preview.memberName shouldBe "Bob"

                val joined = call("POST", "/invites/${info.code}/accept", "bob").parse(BandStateResponse.serializer())
                joined.band.member("m-bob")!!.userId shouldBe "bob"
                joined.band.member("m-bob")!!.role shouldBe Role.MEMBER
                call("GET", "/me", "bob").parse(MeResponse.serializer()).bands.map { it.id } shouldBe listOf("band1")

                withClue("single use") { call("POST", "/invites/${info.code}/accept", "carol").status shouldBe 404 }
                call("GET", "/bands/band1/invites", "alice").parse(InvitesResponse.serializer()).invites.shouldBeEmpty()
            }
        }

        // Someone holding band money cannot become an artist, by invite either.
        expect("an artist invite waits until their money is settled") {
            with(InviteApi()) {
                setup()
                fun pushRaw(id: String, seq: Long, op: com.jacekgajek.kapelusz.ops.Operation): HttpResponse {
                    val request = PushRequest(listOf(OpEnvelope(id, "phone", seq, 1, op)))
                    return call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), request))
                }
                val artist = invite(role = Role.ARTIST).parse(InviteInfo.serializer())
                val players = listOf("m-alice", "m-bob").map { com.jacekgajek.kapelusz.model.Participant(it) }
                val gig = com.jacekgajek.kapelusz.ops.CreateEvent(
                    "gig",
                    "Gig",
                    kotlinx.datetime.LocalDate(2026, 10, 1),
                    participants = players,
                )
                pushRaw("e", 2, gig)
                val door = com.jacekgajek.kapelusz.model.IncomeSource.DOOR
                pushRaw(
                    "h",
                    3,
                    com.jacekgajek.kapelusz.ops.AddHolding("gig", "h", "m-bob", com.jacekgajek.kapelusz.model.Money(10000), door),
                )

                val refused = call("POST", "/invites/${artist.code}/accept", "bob")
                refused.status shouldBe 409
                refused.body!! shouldContain ApiErrors.HOLDS_MONEY
                withClue("and no new artist invite for them") { invite(role = Role.ARTIST).status shouldBe 409 }
                // Nor a viewer invite while they play in an unsettled gig, with its own reason.
                val viewer = invite(role = Role.VIEWER)
                viewer.status shouldBe 409
                viewer.body!! shouldContain ApiErrors.STILL_PLAYS
                invite(role = Role.MEMBER).status shouldBe 200
            }
        }

        expect("new code replaces old and revoke works") {
            with(InviteApi()) {
                setup()
                val first = invite().parse(InviteInfo.serializer())
                val second = invite(role = Role.LEAD).parse(InviteInfo.serializer())
                second.code shouldNotBe first.code
                call("GET", "/invites/${first.code}", "bob").status shouldBe 404
                call("DELETE", "/bands/band1/invites/${second.code}", "alice").status shouldBe 204
                call("GET", "/invites/${second.code}", "bob").status shouldBe 404
            }
        }

        expect("invites expire") {
            with(InviteApi()) {
                setup()
                val info = invite().parse(InviteInfo.serializer())
                now += Api.INVITE_LIFETIME_MS + 1
                call("POST", "/invites/${info.code}/accept", "bob").status shouldBe 404
            }
        }

        expect("only admins invite and only unlinked members") {
            with(InviteApi()) {
                setup()
                withClue("already linked") { invite(memberId = "m-alice").status shouldBe 400 }
                invite(role = Role.OWNER).status shouldBe 400
                // Bob joins as a member and tries to invite someone.
                val code = invite().parse(InviteInfo.serializer()).code
                call("POST", "/invites/$code/accept", "bob")
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, AddMember("m-carol", "Carol"))))))
                invite(memberId = "m-carol", by = "bob").status shouldBe 403
                invite(memberId = "m-carol", by = "mallory").status shouldBe 404
            }
        }

        expect("code lookups are limited per user") {
            with(InviteApi()) {
                setup()
                val code = invite().parse(InviteInfo.serializer()).code
                repeat(Api.MAX_JOIN_ATTEMPTS) { call("GET", "/invites/ZZZZZZ", "mallory").status shouldBe 404 }
                withClue("even the right code is refused now") { call("GET", "/invites/$code", "mallory").status shouldBe 429 }
                now += Api.ATTEMPT_WINDOW_MS + 1
                call("GET", "/invites/$code", "mallory").status shouldBe 200
            }
        }

        expect("parallel guesses cannot exceed the limit") {
            with(InviteApi()) {
                setup()
                val threads = (1..40).map { Thread { call("GET", "/invites/ZZZZZZ", "mallory") } }
                threads.forEach { it.start() }
                threads.forEach { it.join() }
                val attempts = KapeluszJson.decodeFromString(JoinAttempts.serializer(), store.objects.getValue(StorageKeys.joinAttempts("mallory")).content)
                withClue("counted ${attempts.failures}") { attempts.failures shouldBeLessThanOrEqual Api.MAX_JOIN_ATTEMPTS }
            }
        }

        expect("an interrupted accept is completed by the retry") {
            with(InviteApi()) {
                setup()
                val code = invite().parse(InviteInfo.serializer()).code
                // Simulate a crash after the band write: link Bob directly, as the first half of the accept would.
                val failing = object : BlobStore by store {
                    override fun put(key: String, content: String, ifMatch: String?, ifNoneMatch: Boolean): String {
                        if (key == StorageKeys.user("bob")) throw IllegalStateException("crash")
                        return store.put(key, content, ifMatch, ifNoneMatch)
                    }
                }
                runCatching { Api(failing, InMemoryHistoryStore(), { VerifiedUser(it, null, null) }, clock = { now }).handle(HttpRequest("POST", "/invites/$code/accept", mapOf("authorization" to "Bearer bob"))) }
                withClue("the crash left the user record behind") {
                    call("GET", "/me", "bob").parse(MeResponse.serializer()).bands.shouldBeEmpty()
                }

                call("POST", "/invites/$code/accept", "bob").status shouldBe 200
                call("GET", "/me", "bob").parse(MeResponse.serializer()).bands.map { it.id } shouldBe listOf("band1")
                call("GET", "/bands/band1/invites", "alice").parse(InvitesResponse.serializer()).invites.shouldBeEmpty()
            }
        }

        expect("unlinking cancels leftover invites") {
            with(InviteApi()) {
                setup()
                val first = invite().parse(InviteInfo.serializer()).code
                call("POST", "/invites/$first/accept", "bob")
                // A leftover code for Bob's member (e.g. from a race) ...
                val leftover = InviteInfo("QQQQQQ", "band1", "m-bob", Role.LEAD, now + 1000)
                store.put(StorageKeys.invite(leftover.code), KapeluszJson.encodeToString(StoredInvite.serializer(), StoredInvite(leftover, "alice")))
                store.put(StorageKeys.bandInvites("band1"), KapeluszJson.encodeToString(InviteCodes.serializer(), InviteCodes(listOf(leftover.code))))
                // ... is cancelled when the leader unlinks Bob, so nobody can use it to get back in.
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op9", "phone", 9, 9, UnlinkUser("m-bob"))))))
                call("POST", "/invites/QQQQQQ/accept", "mallory").status shouldBe 404
            }
        }

        expect("invites die with their issuer's admin role and with archived members") {
            with(InviteApi()) {
                setup()
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, AddMember("m-carol", "Carol"))))))
                // Carol becomes a leader and issues an invite for Bob, then loses the leader role.
                val carolCode = invite(memberId = "m-carol").parse(InviteInfo.serializer()).code
                call("POST", "/invites/$carolCode/accept", "carol")
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op3", "phone", 3, 3, SetMemberRole("m-carol", Role.LEAD))))))
                val bobCode = invite(by = "carol").parse(InviteInfo.serializer()).code
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op4", "phone", 4, 4, SetMemberRole("m-carol", Role.MEMBER))))))
                call("POST", "/invites/$bobCode/accept", "bob").status shouldBe 404

                val again = invite().parse(InviteInfo.serializer()).code
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op5", "phone", 5, 5, SetMemberArchived("m-bob", true))))))
                call("POST", "/invites/$again/accept", "bob").status shouldBe 404
            }
        }

        expect("history is paged newest first") {
            with(InviteApi()) {
                setup()
                repeat(Api.HISTORY_PAGE + 5) { i ->
                    now += 1
                    call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("r$i", "phone", 2L + i, 0, RenameBand("Name $i"))))))
                }
                val first = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09"))
                    .parse(HistoryResponse.serializer())
                first.entries.size shouldBe Api.HISTORY_PAGE
                withClue("newest first") { first.entries.first().ops.single().opId shouldBe "r${Api.HISTORY_PAGE + 4}" }
                val second = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "before" to first.next!!)).parse(HistoryResponse.serializer())
                second.entries.size shouldBe 6 // 5 renames left + the AddMember from setup
                second.next.shouldBeNull()
                second.entries.last().ops.single().opId shouldBe "op1"

                call("GET", "/bands/band1/log", "mallory").status shouldBe 404
                call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "x")).status shouldBe 400
            }
        }

        expect("a BLIK number is saved but not in the history") {
            with(InviteApi()) {
                setup()
                now += 1
                val pushed = call(
                    "POST",
                    "/bands/band1/ops",
                    "alice",
                    KapeluszJson.encodeToString(
                        PushRequest.serializer(),
                        PushRequest(
                            listOf(
                                OpEnvelope("blik", "phone", 2, 0, com.jacekgajek.kapelusz.ops.SetBlikPhone("m-alice", "600 123 456")),
                                OpEnvelope("account", "phone", 3, 0, com.jacekgajek.kapelusz.ops.SetBankAccount("m-alice", "61 1090 1014 0000 0712 1981 2874")),
                                OpEnvelope("rename", "phone", 4, 0, RenameBand("New name")),
                            ),
                        ),
                    ),
                ).parse(PushResponse.serializer())
                pushed.accepted shouldBe listOf("blik", "account", "rename")
                pushed.band.member("m-alice")!!.blikPhone shouldBe "600123456"
                pushed.band.member("m-alice")!!.bankAccount shouldBe "61109010140000071219812874"
                val logged = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09"))
                    .parse(HistoryResponse.serializer())
                    .entries.flatMap { entry -> entry.ops.map { it.opId } }
                logged shouldContain "rename"
                withClue("who is paid where is not history") { logged shouldNotContain "blik" }
                withClue("nor their account") { logged shouldNotContain "account" }

                // Nor in the members' snapshots of other changes.
                now += 1
                call(
                    "POST",
                    "/bands/band1/ops",
                    "alice",
                    KapeluszJson.encodeToString(
                        PushRequest.serializer(),
                        PushRequest(
                            listOf(
                                OpEnvelope("rename-alice", "phone", 5, 0, com.jacekgajek.kapelusz.ops.RenameMember("m-alice", "Ala")),
                            ),
                        ),
                    ),
                ).parse(PushResponse.serializer())
                val change = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09"))
                    .parse(HistoryResponse.serializer())
                    .entries.first { entry -> entry.ops.any { it.opId == "rename-alice" } }.changes.getValue("rename-alice")
                change.after.members.getValue("m-alice")!!.name shouldBe "Ala"
                change.after.members.getValue("m-alice")!!.blikPhone.shouldBeNull()
                change.before.members.getValue("m-alice")!!.blikPhone.shouldBeNull()
                change.after.members.getValue("m-alice")!!.bankAccount.shouldBeNull()
                change.before.members.getValue("m-alice")!!.bankAccount.shouldBeNull()
            }
        }

        expect("undo restores what the log recorded, not what the client says") {
            with(InviteApi()) {
                setup()
                fun pushRaw(vararg envelopes: OpEnvelope) = call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())))
                    .parse(PushResponse.serializer())
                now += 1
                pushRaw(OpEnvelope("rename", "phone", 10, 0, RenameBand("New name")))
                val entry = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer()).entries.first()
                val change = entry.changes.getValue("rename")
                change.after.settings?.name shouldBe "New name"

                // A made-up change (a different band name to "restore") is replaced by the recorded one.
                val forged = change.copy(before = change.before.copy(settings = change.before.settings!!.copy(name = "Hacked")))
                now += 1
                withClue("op ids are unique in a push") {
                    call(
                        "POST",
                        "/bands/band1/ops",
                        "alice",
                        KapeluszJson.encodeToString(
                            PushRequest.serializer(),
                            PushRequest(
                                listOf(
                                    OpEnvelope("same", "phone", 20, 0, RenameBand("a")),
                                    OpEnvelope("same", "phone", 21, 0, RenameBand("b")),
                                ),
                            ),
                        ),
                    ).status shouldBe 400
                }
                val undone = pushRaw(OpEnvelope("undo", "phone", 11, 0, Undo("rename", RenameBand("whatever"), forged, entry.id)))
                undone.accepted shouldBe listOf("undo")
                undone.band.name shouldBe "Night Owls"

                // An undo of something the log does not know is refused.
                now += 1
                val unknown = pushRaw(OpEnvelope("undo2", "phone", 12, 0, Undo("nope", RenameBand("x"), forged, entry.id)))
                unknown.rejected.getValue("undo2").reason shouldBe RejectionReason.NOT_UNDOABLE
                // Not in a log entry of this band: nothing to undo.
                now += 1
                val elsewhere =
                    pushRaw(OpEnvelope("undo3", "phone", 13, 0, Undo("rename", RenameBand("x"), forged, "../../other/state.json")))
                elsewhere.rejected.getValue("undo3").reason shouldBe RejectionReason.NOT_UNDOABLE
            }
        }

        expect("a joining member brings their photo and a removed one takes it away") {
            with(InviteApi()) {
                setup()
                val photo = "https://lh3.googleusercontent.com/a/bob=s96-c"
                // Bob signed in with Google before (his photo is kept with his user).
                val google = Api(
                    store,
                    InMemoryHistoryStore(),
                    { token -> VerifiedUser(token, null, null, picture = photo) },
                    clock = { now },
                )
                google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer bob"))).status shouldBe 200
                val code = invite().parse(InviteInfo.serializer()).code
                val joined = call("POST", "/invites/$code/accept", "bob").parse(BandStateResponse.serializer())
                joined.band.member("m-bob")?.pictureUrl shouldBe photo

                // Removed from the band: the photo goes, and a later sign-in does not bring it back.
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op9", "phone", 9, 9, SetMemberArchived("m-bob", true))))))
                google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer bob")))
                val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
                band.member("m-bob")?.pictureUrl.shouldBeNull()
            }
        }

        expect("only Google photo addresses are kept") {
            Api.isGooglePhoto("https://lh3.googleusercontent.com/a/ACg8ocK=s96-c") shouldBe true
            for (bad in listOf(
                "http://lh3.googleusercontent.com/a/x",
                "https://lh3.googleusercontent.com.evil.io/a",
                "https://evil.io/lh3.googleusercontent.com/a",
                "https://user@lh3.googleusercontent.com/a",
                "https://lh3.googleusercontent.com/" + "a".repeat(500),
                "https://lh3.googleusercontent.com/a\"<script>",
            )) {
                withClue(bad) { Api.isGooglePhoto(bad) shouldBe false }
            }
        }
    })
