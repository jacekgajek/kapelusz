package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.OpEnvelope
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaveBandTest {
    private val store = InMemoryBlobStore()
    private val api = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, clock = { 1_790_000_000_000 })

    private fun call(method: String, path: String, user: String, body: String? = null) =
        api.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body))

    private fun invite(member: String) = KapeluszJson.decodeFromString(
        InviteInfo.serializer(),
        call("POST", "/bands/band1/invites", "alice", KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(member, Role.MEMBER))).body!!,
    ).code

    /** Alice owns the band; Bob joined it and recorded a cost. */
    private fun setup() {
        call("POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        val ops = listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob")))
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
        assertEquals(200, call("POST", "/invites/${invite("m-bob")}/accept", "bob").status)
        val cost = listOf(OpEnvelope("op2", "phone", 1, 1, AddCost("c1", "m-bob", Money(5000), LocalDate(2026, 9, 1))))
        call("POST", "/bands/band1/ops", "bob", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(cost)))
    }

    private fun band() = KapeluszJson.decodeFromString(BandStateResponse.serializer(), call("GET", "/bands/band1/state", "alice").body!!).band

    @Test
    fun aMemberLeavesAndTheirEntryStays() {
        setup()
        assertEquals(204, call("POST", "/bands/band1/leave", "bob").status)

        val bob = band().member("m-bob")!!
        assertNull(bob.userId, "the account is unlinked")
        assertEquals(1, band().costs.count { it.memberId == "m-bob" }, "what they recorded stays")
        assertTrue(KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", "bob").body!!).bands.isEmpty())
        assertTrue(call("GET", "/bands/band1/state", "bob").body!!.contains(ApiErrors.BAND_NOT_FOUND))
        assertEquals(404, call("POST", "/bands/band1/leave", "bob").status, "a second time: not a member any more")
        // A new invite brings them back.
        assertEquals(200, call("POST", "/invites/${invite("m-bob")}/accept", "bob").status)
        assertEquals("bob", band().member("m-bob")!!.userId)
    }

    @Test
    fun theOwnerCannotLeave() {
        setup()
        val refused = call("POST", "/bands/band1/leave", "alice")
        assertEquals(409, refused.status)
        assertTrue(refused.body!!.contains(ApiErrors.OWNER_CANNOT_LEAVE))
        assertEquals("alice", band().owner?.userId)
    }
}
