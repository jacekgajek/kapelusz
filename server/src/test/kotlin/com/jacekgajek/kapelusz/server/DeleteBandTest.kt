package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.OpEnvelope
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteBandTest {
    private val store = InMemoryBlobStore()
    private val api = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, clock = { 1_790_000_000_000 })

    private fun call(method: String, path: String, user: String, body: String? = null) =
        api.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body))

    /** Alice owns the band, Bob joined it via an invite, Carol has an open invite; a cost has a photo. */
    private fun setup(): String {
        call("POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        val ops = listOf(
            AddMember("m-bob", "Bob"), AddMember("m-carol", "Carol"),
            AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)),
            AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 100)),
        ).mapIndexed { i, op -> OpEnvelope("op$i", "phone", i + 1L, 1, op) }
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
        store.files += "bands/band1/attachments/a1.jpg"
        fun invite(member: String) = KapeluszJson.decodeFromString(InviteInfo.serializer(), call("POST", "/bands/band1/invites", "alice", KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(member, Role.ADMIN))).body!!)
        assertEquals(200, call("POST", "/invites/${invite("m-bob").code}/accept", "bob").status)
        return invite("m-carol").code
    }

    @Test
    fun theOwnerDeletesEverythingAndMembersAreTold() {
        val carolsCode = setup()
        assertEquals(403, call("DELETE", "/bands/band1", "bob").status, "an admin is not enough")
        assertEquals(204, call("DELETE", "/bands/band1", "alice").status)

        assertEquals(listOf("bands/band1/deleted.json"), store.list("bands/band1/") + store.files.filter { it.startsWith("bands/band1/") })
        assertEquals(404, call("GET", "/invites/$carolsCode", "carol").status, "open invites are gone")
        for (user in listOf("alice", "bob")) {
            assertTrue(KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", user).body!!).bands.isEmpty())
            val gone = call("GET", "/bands/band1/state", user)
            assertEquals(404, gone.status)
            assertTrue(gone.body!!.contains(ApiErrors.BAND_DELETED), "former members learn it was deleted")
        }
        assertTrue(call("GET", "/bands/band1/state", "mallory").body!!.contains(ApiErrors.BAND_NOT_FOUND), "others learn nothing")
    }
}
