package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.CreateInviteRequest
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.model.Attachment
import com.jacekgajek.kapelusz.model.AttachmentKind
import com.jacekgajek.kapelusz.model.BandDeletion
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.OpEnvelope
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class DeleteBandTest {
    private val store = InMemoryBlobStore()
    private var now = 1_790_000_000_000
    private val api = Api(store, InMemoryHistoryStore(), { token -> VerifiedUser(token, null, null) }, clock = { now })

    private fun call(method: String, path: String, user: String, body: String? = null) = api.handle(HttpRequest(method, path, mapOf("authorization" to "Bearer $user"), body))

    /** Alice owns the band, Bob joined it via an invite, Carol has an open invite; a cost has a photo. */
    private fun setup(): String {
        call("POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        val ops = listOf(
            AddMember("m-bob", "Bob"),
            AddMember("m-carol", "Carol"),
            AddCost("c1", "m-alice", Money(5000), LocalDate(2026, 9, 1)),
            AddAttachment("c1", Attachment("a1", AttachmentKind.IMAGE, "r.jpg", 100)),
        ).mapIndexed { i, op -> OpEnvelope("op$i", "phone", i + 1L, 1, op) }
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
        store.files += "bands/band1/attachments/a1.jpg"
        fun invite(member: String) = KapeluszJson.decodeFromString(InviteInfo.serializer(), call("POST", "/bands/band1/invites", "alice", KapeluszJson.encodeToString(CreateInviteRequest.serializer(), CreateInviteRequest(member, Role.LEAD))).body!!)
        assertEquals(200, call("POST", "/invites/${invite("m-bob").code}/accept", "bob").status)
        return invite("m-carol").code
    }

    private fun state(user: String) = KapeluszJson.decodeFromString(BandStateResponse.serializer(), call("GET", "/bands/band1/state", user).body!!).band

    private fun push(user: String, op: Operation) = call("POST", "/bands/band1/ops", user, KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("$user-$now", "tablet", now, now, op)))))

    @Test
    fun theOwnerAsksAndTheBandGoesAWeekLaterForEveryone() {
        val carolsCode = setup()
        assertEquals(403, call("DELETE", "/bands/band1", "bob").status, "a leader is not enough")
        assertEquals(204, call("DELETE", "/bands/band1", "alice").status)
        assertEquals(BandDeletion("m-alice", now, now + BandDeletion.DELAY_MS), state("bob").deletion, "the band works on, saying when it goes")
        assertEquals(204, call("DELETE", "/bands/band1", "alice").status)
        assertEquals(now + BandDeletion.DELAY_MS, state("bob").deletion!!.deleteAt, "asked again: still the first time")

        now += BandDeletion.DELAY_MS
        // Loading it is what deletes it.
        call("GET", "/bands/band1/state", "bob")
        assertEquals(listOf("bands/band1/deleted.json"), store.list("bands/band1/") + store.files.filter { it.startsWith("bands/band1/") })
        assertEquals(404, call("GET", "/invites/$carolsCode", "carol").status, "open invites are gone")
        for (user in listOf("alice", "bob")) {
            assertTrue(KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", user).body!!).bands.isEmpty())
            val gone = call("GET", "/bands/band1/state", user)
            assertEquals(404, gone.status)
            assertTrue(gone.body!!.contains(ApiErrors.BAND_DELETED), "former members learn it was deleted")
            assertTrue(gone.body!!.contains("by Alice"))
        }
        assertTrue(call("GET", "/bands/band1/state", "mallory").body!!.contains(ApiErrors.BAND_NOT_FOUND), "others learn nothing")
    }

    @Test
    fun aLeaderKeepsTheBandAndOnlyTheServerSetsTheTime() {
        setup()
        call("DELETE", "/bands/band1", "alice")
        val forged = push("alice", ScheduleBandDeletion(BandDeletion("m-alice", 0, 0)))
        assertTrue(forged.body!!.contains("ROLE_REQUIRED"), "no deletion pushed with a time of one's own")
        push("bob", CancelBandDeletion)
        assertNull(state("bob").deletion)
        now += BandDeletion.DELAY_MS
        assertEquals(200, call("GET", "/bands/band1/state", "bob").status, "kept")
        assertEquals(ScheduledDeletions(), KapeluszJson.decodeFromString(ScheduledDeletions.serializer(), store.get(StorageKeys.SCHEDULED_DELETIONS)!!.content))
    }

    @Test
    fun aBandNobodyOpensGoesTooOnTheHourlySweep() {
        setup()
        call("DELETE", "/bands/band1", "alice")
        now += BandDeletion.DELAY_MS + BandDeletions.SWEEP_EVERY_MS
        // Any request, about anything.
        call("GET", "/me", "mallory")
        assertTrue(store.list("bands/band1/") == listOf("bands/band1/deleted.json"))
        assertEquals(ScheduledDeletions(), KapeluszJson.decodeFromString(ScheduledDeletions.serializer(), store.get(StorageKeys.SCHEDULED_DELETIONS)!!.content))
    }

    @Test
    fun theListOfBandsWaitingFollowsTheBand() {
        setup()
        fun waiting() = KapeluszJson.decodeFromString(ScheduledDeletions.serializer(), store.get(StorageKeys.SCHEDULED_DELETIONS)!!.content).bands
        call("DELETE", "/bands/band1", "alice")
        push("bob", CancelBandDeletion)
        push("alice", CancelBandDeletion)
        assertNull(state("bob").deletion, "kept twice: the second has nothing to do, and isn't refused")
        now += 1000
        call("DELETE", "/bands/band1", "alice")
        assertEquals(mapOf("band1" to now + BandDeletion.DELAY_MS), waiting(), "asked again: the new time")
    }
}
