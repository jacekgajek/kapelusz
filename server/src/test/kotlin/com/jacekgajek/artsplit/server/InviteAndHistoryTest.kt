package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.api.BandStateResponse
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.CreateInviteRequest
import com.jacekgajek.artsplit.api.HistoryResponse
import com.jacekgajek.artsplit.api.InviteInfo
import com.jacekgajek.artsplit.api.InvitePreview
import com.jacekgajek.artsplit.api.InvitesResponse
import com.jacekgajek.artsplit.api.MeResponse
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.OpEnvelope
import com.jacekgajek.artsplit.ops.RenameBand
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
        return ArtSplitJson.decodeFromString(serializer, body!!)
    }

    /** Alice owns the band; Bob is an unlinked roster member. */
    private fun setup() {
        call("POST", "/bands", "alice", ArtSplitJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        call("POST", "/bands/band1/ops", "alice", ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob"))))))
    }

    private fun invite(memberId: String = "m-bob", role: Role = Role.MEMBER, by: String = "alice") =
        call("POST", "/bands/band1/invites", by, ArtSplitJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(memberId, role)))

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
        call("POST", "/bands/band1/ops", "alice", ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, AddMember("m-carol", "Carol"))))))
        assertEquals(403, invite(memberId = "m-carol", by = "bob").status)
        assertEquals(404, invite(memberId = "m-carol", by = "mallory").status)
    }

    @Test
    fun guessingCodesIsLimited() {
        setup()
        val code = invite().parse(InviteInfo.serializer()).code
        repeat(Api.MAX_FAILED_JOIN_ATTEMPTS) { assertEquals(404, call("GET", "/invites/ZZZZZZ", "mallory").status) }
        assertEquals(429, call("GET", "/invites/$code", "mallory").status, "even the right code is refused now")
        now += Api.ATTEMPT_WINDOW_MS + 1
        assertEquals(200, call("GET", "/invites/$code", "mallory").status)
    }

    @Test
    fun historyListsAcceptedPushesOfTheMonth() {
        setup()
        now += 1
        call("POST", "/bands/band1/ops", "alice", ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("op2", "phone", 2, 2, RenameBand("Renamed"))))))
        val history = call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "2026-09")).parse(HistoryResponse.serializer())
        assertEquals(listOf("op1", "op2"), history.entries.flatMap { it.ops }.map { it.opId })
        assertEquals("m-alice", history.entries.first().memberId)
        assertEquals(404, call("GET", "/bands/band1/log", "mallory").status)
        assertEquals(400, call("GET", "/bands/band1/log", "alice", query = mapOf("month" to "x")).status)
    }
}
