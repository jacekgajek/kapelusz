package com.jacekgajek.kapelusz.server

import kotlin.test.assertFalse
import kotlin.test.assertNull
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.HistoryResponse
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.InvitePreview
import com.jacekgajek.kapelusz.api.InvitesResponse
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.UnlinkUser
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InviteAndHistoryTest {
    private val store = InMemoryBlobStore()
    private var now = 1_790_000_000_000
    private val api = Api(store, { token -> VerifiedUser(token, null, null) }, clock = { now })

    private fun call(method: String, path: String, user: String, body: String? = null, query: Map<String, String> = emptyMap()) =
        api.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body, query))

    private fun <T> HttpResponse.parse(serializer: KSerializer<T>): T {
        assertEquals(200, status, body)
        return KapeluszJson.decodeFromString(serializer, body!!)
    }

    /** Alice owns the band; Bob is an unlinked roster member. */
    private fun setup() {
        call("POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob"))))))
    }

    private fun invite(memberId: String = "m-bob", role: Role = Role.MEMBER, by: String = "alice") =
        call("POST", "/bands/band1/invites", by, KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(memberId, role)))

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

    /** ITERATION_4 §1.1: someone holding band money cannot become an artist, by invite either. */
    @Test
    fun anArtistInviteWaitsUntilTheirMoneyIsSettled() {
        setup()
        fun push(id: String, seq: Long, op: com.jacekgajek.kapelusz.ops.Operation) =
            call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope(id, "phone", seq, 1, op)))))
        val artist = invite(role = Role.ARTIST).parse(InviteInfo.serializer())
        push("e", 2, com.jacekgajek.kapelusz.ops.CreateEvent("gig", "Gig", kotlinx.datetime.LocalDate(2026, 10, 1), participants = listOf(com.jacekgajek.kapelusz.model.Participant("m-alice"), com.jacekgajek.kapelusz.model.Participant("m-bob"))))
        push("h", 3, com.jacekgajek.kapelusz.ops.AddHolding("gig", "h", "m-bob", com.jacekgajek.kapelusz.model.Money(10000), com.jacekgajek.kapelusz.model.IncomeSource.DOOR))

        val refused = call("POST", "/invites/${artist.code}/accept", "bob")
        assertEquals(409, refused.status)
        assertTrue(ApiErrors.HOLDS_MONEY in refused.body!!)
        assertEquals(409, invite(role = Role.ARTIST).status, "and no new artist invite for them")
        // V5-M1 B2: nor a viewer invite while they play in an unsettled gig, with its own reason.
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
        runCatching { Api(failing, { VerifiedUser(it, null, null) }, clock = { now }).handle(HttpRequest("POST", "/invites/$code/accept", mapOf("authorization" to "Bearer bob"))) }
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
        val leftover = InviteInfo("QQQQQQ", "band1", "m-bob", Role.ADMIN, now + 1000)
        store.put(StorageKeys.invite(leftover.code), KapeluszJson.encodeToString(StoredInvite.serializer(), StoredInvite(leftover, "alice")))
        store.put(StorageKeys.bandInvites("band1"), KapeluszJson.encodeToString(InviteCodes.serializer(), InviteCodes(listOf(leftover.code))))
        // ... is cancelled when the admin unlinks Bob, so nobody can use it to get back in.
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op9", "phone", 9, 9, UnlinkUser("m-bob"))))))
        assertEquals(404, call("POST", "/invites/QQQQQQ/accept", "mallory").status)
    }

    @Test
    fun invitesDieWithTheirIssuersAdminRoleAndWithArchivedMembers() {
        setup()
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, AddMember("m-carol", "Carol"))))))
        // Carol becomes an admin and issues an invite for Bob, then loses the admin role.
        val carolCode = invite(memberId = "m-carol").parse(InviteInfo.serializer()).code
        call("POST", "/invites/$carolCode/accept", "carol")
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op3", "phone", 3, 3, SetMemberRole("m-carol", Role.ADMIN))))))
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
    fun undoRestoresWhatTheLogRecordedNotWhatTheClientSays() {
        setup()
        fun push(vararg envelopes: OpEnvelope) = call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(envelopes.toList())))
            .parse(PushResponse.serializer())
        now += 1
        push(OpEnvelope("rename", "phone", 10, 0, RenameBand("New name")))
        val entry = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer()).entries.first()
        val change = entry.changes.getValue("rename")
        assertEquals("New name", change.after.settings?.name)

        // A made-up change (a different band name to "restore") is replaced by the recorded one.
        val forged = change.copy(before = change.before.copy(settings = change.before.settings!!.copy(name = "Hacked")))
        now += 1
        assertEquals(400, call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("same", "phone", 20, 0, RenameBand("a")), OpEnvelope("same", "phone", 21, 0, RenameBand("b")))))).status, "op ids are unique in a push")
        val undone = push(OpEnvelope("undo", "phone", 11, 0, Undo("rename", RenameBand("whatever"), forged, entry.id)))
        assertEquals(listOf("undo"), undone.accepted)
        assertEquals("Night Owls", undone.band.name)

        // An undo of something the log does not know is refused.
        now += 1
        val unknown = push(OpEnvelope("undo2", "phone", 12, 0, Undo("nope", RenameBand("x"), forged, entry.id)))
        assertEquals(RejectionReason.NOT_UNDOABLE, unknown.rejected.getValue("undo2").reason)
        // Not in a log entry of this band: nothing to undo.
        now += 1
        val elsewhere = push(OpEnvelope("undo3", "phone", 13, 0, Undo("rename", RenameBand("x"), forged, "../../other/state.json")))
        assertEquals(RejectionReason.NOT_UNDOABLE, elsewhere.rejected.getValue("undo3").reason)
    }

    @Test
    fun aJoiningMemberBringsTheirPhotoAndARemovedOneTakesItAway() {
        setup()
        val photo = "https://lh3.googleusercontent.com/a/bob=s96-c"
        // Bob signed in with Google before (his photo is kept with his user).
        val google = Api(store, { token -> VerifiedUser(token, null, null, picture = photo) }, clock = { now })
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
        for (bad in listOf("http://lh3.googleusercontent.com/a/x", "https://lh3.googleusercontent.com.evil.io/a", "https://evil.io/lh3.googleusercontent.com/a",
            "https://user@lh3.googleusercontent.com/a", "https://lh3.googleusercontent.com/" + "a".repeat(500), "https://lh3.googleusercontent.com/a\"<script>")) {
            assertFalse(Api.isGooglePhoto(bad), bad)
        }
    }
}
