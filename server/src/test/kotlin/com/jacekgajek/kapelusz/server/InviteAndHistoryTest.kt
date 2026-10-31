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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteAndHistoryTest : ApiHarness() {
    /** Alice owns the band; Bob is an unlinked roster member. */
    private fun setup() {
        createBand()
        push("alice", AddMember("m-bob", "Bob"))
    }

    @Test
    fun inviteLifecycle() {
        setup()
        val info = invite().parse(InviteInfo.serializer())
        assertTrue(info.code.matches(Regex("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}")))
        assertEquals(listOf(info.code), call("GET", "/bands/band1/invites", "alice").parse(InvitesResponse.serializer()).invites.map { it.code })

        val preview = call("GET", "/invites/${info.code.lowercase()}", "bob").parse(InvitePreview.serializer())
        assertEquals("Night Owls", preview.bandName)
        assertEquals("Bob", preview.memberName)

        val joined = call("POST", "/invites/${info.code}/accept", "bob").parse(BandStateResponse.serializer())
        assertEquals("bob", joined.band.member("m-bob")!!.userId)
        assertEquals(Role.MEMBER, joined.band.member("m-bob")!!.role)
        assertEquals(listOf("band1"), call("GET", "/me", "bob").parse(MeResponse.serializer()).bands.map { it.id })

        assertEquals(404, call("POST", "/invites/${info.code}/accept", "carol").status, "single use")
        assertTrue(call("GET", "/bands/band1/invites", "alice").parse(InvitesResponse.serializer()).invites.isEmpty())
    }

    /** Someone holding band money cannot become an artist, by invite either. */
    @Test
    fun anArtistInviteWaitsUntilTheirMoneyIsSettled() {
        setup()
        fun pushRaw(id: String, seq: Long, op: com.jacekgajek.kapelusz.ops.Operation): HttpResponse {
            val request = PushRequest(listOf(OpEnvelope(id, "phone", seq, 1, op)))
            return call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), request))
        }
        val artist = invite(role = Role.ARTIST).parse(InviteInfo.serializer())
        val players = listOf("m-alice", "m-bob").map { com.jacekgajek.kapelusz.model.Participant(it) }
        val gig = com.jacekgajek.kapelusz.ops.CreateEvent("gig", "Gig", kotlinx.datetime.LocalDate(2026, 10, 1), participants = players)
        pushRaw("e", 2, gig)
        val door = com.jacekgajek.kapelusz.model.IncomeSource.DOOR
        pushRaw("h", 3, com.jacekgajek.kapelusz.ops.AddHolding("gig", "h", "m-bob", com.jacekgajek.kapelusz.model.Money(10000), door))

        val refused = call("POST", "/invites/${artist.code}/accept", "bob")
        assertEquals(409, refused.status)
        assertTrue(ApiErrors.HOLDS_MONEY in refused.body!!)
        assertEquals(409, invite(role = Role.ARTIST).status, "and no new artist invite for them")
        // Nor a viewer invite while they play in an unsettled gig, with its own reason.
        val viewer = invite(role = Role.VIEWER)
        assertEquals(409, viewer.status)
        assertTrue(ApiErrors.STILL_PLAYS in viewer.body!!)
        assertEquals(200, invite(role = Role.MEMBER).status)
    }

    @Test
    fun newCodeReplacesOldAndRevokeWorks() {
        setup()
        val first = invite().parse(InviteInfo.serializer())
        val second = invite(role = Role.LEAD).parse(InviteInfo.serializer())
        assertNotEquals(first.code, second.code)
        assertEquals(404, call("GET", "/invites/${first.code}", "bob").status)
        assertEquals(204, call("DELETE", "/bands/band1/invites/${second.code}", "alice").status)
        assertEquals(404, call("GET", "/invites/${second.code}", "bob").status)
    }

    @Test
    fun invitesExpire() {
        setup()
        val info = invite().parse(InviteInfo.serializer())
        now += Api.INVITE_LIFETIME_MS + 1
        assertEquals(404, call("POST", "/invites/${info.code}/accept", "bob").status)
    }

    @Test
    fun onlyAdminsInviteAndOnlyUnlinkedMembers() {
        setup()
        assertEquals(400, invite(memberId = "m-alice").status, "already linked")
        assertEquals(400, invite(role = Role.OWNER).status)
        // Bob joins as a member and tries to invite someone.
        val code = invite().parse(InviteInfo.serializer()).code
        call("POST", "/invites/$code/accept", "bob")
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, AddMember("m-carol", "Carol"))))))
        assertEquals(403, invite(memberId = "m-carol", by = "bob").status)
        assertEquals(404, invite(memberId = "m-carol", by = "mallory").status)
    }

    @Test
    fun codeLookupsAreLimitedPerUser() {
        setup()
        val code = invite().parse(InviteInfo.serializer()).code
        repeat(Api.MAX_JOIN_ATTEMPTS) { assertEquals(404, call("GET", "/invites/ZZZZZZ", "mallory").status) }
        assertEquals(429, call("GET", "/invites/$code", "mallory").status, "even the right code is refused now")
        now += Api.ATTEMPT_WINDOW_MS + 1
        assertEquals(200, call("GET", "/invites/$code", "mallory").status)
    }

    @Test
    fun parallelGuessesCannotExceedTheLimit() {
        setup()
        val threads = (1..40).map { Thread { call("GET", "/invites/ZZZZZZ", "mallory") } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val attempts = KapeluszJson.decodeFromString(JoinAttempts.serializer(), store.objects.getValue(StorageKeys.joinAttempts("mallory")).content)
        assertTrue(attempts.failures <= Api.MAX_JOIN_ATTEMPTS, "counted ${attempts.failures}")
    }

    @Test
    fun anInterruptedAcceptIsCompletedByTheRetry() {
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
        assertTrue(call("GET", "/me", "bob").parse(MeResponse.serializer()).bands.isEmpty(), "the crash left the user record behind")

        assertEquals(200, call("POST", "/invites/$code/accept", "bob").status)
        assertEquals(listOf("band1"), call("GET", "/me", "bob").parse(MeResponse.serializer()).bands.map { it.id })
        assertTrue(call("GET", "/bands/band1/invites", "alice").parse(InvitesResponse.serializer()).invites.isEmpty())
    }

    @Test
    fun unlinkingCancelsLeftoverInvites() {
        setup()
        val first = invite().parse(InviteInfo.serializer()).code
        call("POST", "/invites/$first/accept", "bob")
        // A leftover code for Bob's member (e.g. from a race) ...
        val leftover = InviteInfo("QQQQQQ", "band1", "m-bob", Role.LEAD, now + 1000)
        store.put(StorageKeys.invite(leftover.code), KapeluszJson.encodeToString(StoredInvite.serializer(), StoredInvite(leftover, "alice")))
        store.put(StorageKeys.bandInvites("band1"), KapeluszJson.encodeToString(InviteCodes.serializer(), InviteCodes(listOf(leftover.code))))
        // ... is cancelled when the leader unlinks Bob, so nobody can use it to get back in.
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op9", "phone", 9, 9, UnlinkUser("m-bob"))))))
        assertEquals(404, call("POST", "/invites/QQQQQQ/accept", "mallory").status)
    }

    @Test
    fun invitesDieWithTheirIssuersAdminRoleAndWithArchivedMembers() {
        setup()
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, AddMember("m-carol", "Carol"))))))
        // Carol becomes a leader and issues an invite for Bob, then loses the leader role.
        val carolCode = invite(memberId = "m-carol").parse(InviteInfo.serializer()).code
        call("POST", "/invites/$carolCode/accept", "carol")
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op3", "phone", 3, 3, SetMemberRole("m-carol", Role.LEAD))))))
        val bobCode = invite(by = "carol").parse(InviteInfo.serializer()).code
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op4", "phone", 4, 4, SetMemberRole("m-carol", Role.MEMBER))))))
        assertEquals(404, call("POST", "/invites/$bobCode/accept", "bob").status)

        val again = invite().parse(InviteInfo.serializer()).code
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op5", "phone", 5, 5, SetMemberArchived("m-bob", true))))))
        assertEquals(404, call("POST", "/invites/$again/accept", "bob").status)
    }

    @Test
    fun historyIsPagedNewestFirst() {
        setup()
        repeat(Api.HISTORY_PAGE + 5) { i ->
            now += 1
            call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("r$i", "phone", 2L + i, 0, RenameBand("Name $i"))))))
        }
        val first = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer())
        assertEquals(Api.HISTORY_PAGE, first.entries.size)
        assertEquals("r${Api.HISTORY_PAGE + 4}", first.entries.first().ops.single().opId, "newest first")
        val second = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09", "before" to first.next!!)).parse(HistoryResponse.serializer())
        assertEquals(6, second.entries.size) // 5 renames left + the AddMember from setup
        assertEquals(null, second.next)
        assertEquals("op1", second.entries.last().ops.single().opId)

        assertEquals(404, call("GET", "/bands/band1/log", "mallory").status)
        assertEquals(400, call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "x")).status)
    }

    @Test
    fun aBlikNumberIsSavedButNotInTheHistory() {
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
        assertEquals(listOf("blik", "account", "rename"), pushed.accepted)
        assertEquals("600123456", pushed.band.member("m-alice")!!.blikPhone)
        assertEquals("61109010140000071219812874", pushed.band.member("m-alice")!!.bankAccount)
        val logged = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer())
            .entries.flatMap { entry -> entry.ops.map { it.opId } }
        assertEquals(true, "rename" in logged)
        assertEquals(false, "blik" in logged, "who is paid where is not history")
        assertEquals(false, "account" in logged, "nor their account")

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
        val change = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer())
            .entries.first { entry -> entry.ops.any { it.opId == "rename-alice" } }.changes.getValue("rename-alice")
        assertEquals("Ala", change.after.members.getValue("m-alice")!!.name)
        assertEquals(null, change.after.members.getValue("m-alice")!!.blikPhone)
        assertEquals(null, change.before.members.getValue("m-alice")!!.blikPhone)
        assertEquals(null, change.after.members.getValue("m-alice")!!.bankAccount)
        assertEquals(null, change.before.members.getValue("m-alice")!!.bankAccount)
    }

    @Test
    fun undoRestoresWhatTheLogRecordedNotWhatTheClientSays() {
        setup()
        fun pushRaw(vararg envelopes: OpEnvelope) = call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())))
            .parse(PushResponse.serializer())
        now += 1
        pushRaw(OpEnvelope("rename", "phone", 10, 0, RenameBand("New name")))
        val entry = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer()).entries.first()
        val change = entry.changes.getValue("rename")
        assertEquals("New name", change.after.settings?.name)

        // A made-up change (a different band name to "restore") is replaced by the recorded one.
        val forged = change.copy(before = change.before.copy(settings = change.before.settings!!.copy(name = "Hacked")))
        now += 1
        assertEquals(400, call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("same", "phone", 20, 0, RenameBand("a")), OpEnvelope("same", "phone", 21, 0, RenameBand("b")))))).status, "op ids are unique in a push")
        val undone = pushRaw(OpEnvelope("undo", "phone", 11, 0, Undo("rename", RenameBand("whatever"), forged, entry.id)))
        assertEquals(listOf("undo"), undone.accepted)
        assertEquals("Night Owls", undone.band.name)

        // An undo of something the log does not know is refused.
        now += 1
        val unknown = pushRaw(OpEnvelope("undo2", "phone", 12, 0, Undo("nope", RenameBand("x"), forged, entry.id)))
        assertEquals(RejectionReason.NOT_UNDOABLE, unknown.rejected.getValue("undo2").reason)
        // Not in a log entry of this band: nothing to undo.
        now += 1
        val elsewhere = pushRaw(OpEnvelope("undo3", "phone", 13, 0, Undo("rename", RenameBand("x"), forged, "../../other/state.json")))
        assertEquals(RejectionReason.NOT_UNDOABLE, elsewhere.rejected.getValue("undo3").reason)
    }

    @Test
    fun aJoiningMemberBringsTheirPhotoAndARemovedOneTakesItAway() {
        setup()
        val photo = "https://lh3.googleusercontent.com/a/bob=s96-c"
        // Bob signed in with Google before (his photo is kept with his user).
        val google = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null, picture = photo) }, clock = { now })
        assertEquals(200, google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer bob"))).status)
        val code = invite().parse(InviteInfo.serializer()).code
        val joined = call("POST", "/invites/$code/accept", "bob").parse(BandStateResponse.serializer())
        assertEquals(photo, joined.band.member("m-bob")?.pictureUrl)

        // Removed from the band: the photo goes, and a later sign-in does not bring it back.
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op9", "phone", 9, 9, SetMemberArchived("m-bob", true))))))
        google.handle(HttpRequest("POST", "/session", mapOf("authorization" to "Bearer bob")))
        val band = KapeluszJson.decodeFromString(Band.serializer(), store.objects.getValue(StorageKeys.band("band1")).content)
        assertNull(band.member("m-bob")?.pictureUrl)
    }

    @Test
    fun onlyGooglePhotoAddressesAreKept() {
        assertTrue(Api.isGooglePhoto("https://lh3.googleusercontent.com/a/ACg8ocK=s96-c"))
        for (bad in listOf(
            "http://lh3.googleusercontent.com/a/x",
            "https://lh3.googleusercontent.com.evil.io/a",
            "https://evil.io/lh3.googleusercontent.com/a",
            "https://user@lh3.googleusercontent.com/a",
            "https://lh3.googleusercontent.com/" + "a".repeat(500),
            "https://lh3.googleusercontent.com/a\"<script>",
        )) {
            assertFalse(Api.isGooglePhoto(bad), bad)
        }
    }
}
