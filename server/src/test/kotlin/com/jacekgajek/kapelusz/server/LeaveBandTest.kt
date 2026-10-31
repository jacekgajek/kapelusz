package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.BandStateResponse
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.api.MeResponse
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.UnlinkUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class LeaveBandTest : ApiHarness() {
    /** Alice owns the band; Bob joined it and recorded a cost. */
    private fun setup() {
        createBand()
        val ops = listOf(OpEnvelope("op1", "phone", 1, 1, AddMember("m-bob", "Bob")))
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
        assertEquals(200, call("POST", "/invites/${inviteCode("m-bob")}/accept", "bob").status)
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
        assertEquals(200, call("POST", "/invites/${inviteCode("m-bob")}/accept", "bob").status)
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

    @Test
    fun theHistoryNamesWhoLeft() {
        setup()
        assertEquals(204, call("POST", "/bands/band1/leave", "bob").status)
        val entry = history.bands["band1"]!!.values.map { KapeluszJson.decodeFromString(HistoryEntry.serializer(), it) }
            .single { it.ops.single().op == UnlinkUser("m-bob") }
        assertEquals("m-bob", entry.memberId, "the author is who left, not unknown")
    }

    @Test
    fun leavingTheLastBandLeavesOnlyTheAccountId() {
        setup()
        subscribeAndGivePhoto("bob")
        assertTrue(store.get("users/bob-join-attempts.json") != null, "from accepting the invite")
        assertEquals(204, call("POST", "/bands/band1/leave", "bob").status)

        val record = KapeluszJson.decodeFromString(UserRecord.serializer(), store.get("users/bob.json")!!.content)
        assertEquals(UserRecord("bob", sessionsValidFrom = record.sessionsValidFrom), record, "no bands, no photo")
        assertNull(store.get("users/bob-calendar.json"))
        assertTrue(store.list("calendar/").isEmpty(), "the feed link is gone")
        assertNull(store.get("users/bob-join-attempts.json"))
    }

    @Test
    fun leavingOneOfTwoBandsKeepsTheAccountAsItIs() {
        setup()
        call("POST", "/bands", "bob", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band2", "Larks", "PLN", "m-bob2", "Bob")))
        subscribeAndGivePhoto("bob")
        val before = store.get("users/bob.json")!!.content
        assertEquals(204, call("POST", "/bands/band1/leave", "bob").status)

        val record = KapeluszJson.decodeFromString(UserRecord.serializer(), store.get("users/bob.json")!!.content)
        assertEquals(KapeluszJson.decodeFromString(UserRecord.serializer(), before).copy(bandIds = listOf("band2")), record)
        assertTrue(store.get("users/bob-calendar.json") != null, "the calendar keeps working for band2")
        assertTrue(store.list("calendar/").isNotEmpty())
    }

    @Test
    fun beingUnlinkedByALeaderIsLikeLeaving() {
        setup()
        subscribeAndGivePhoto("bob")
        val unlink = listOf(OpEnvelope("op9", "phone", 9, 1, UnlinkUser("m-bob")))
        assertEquals(200, call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(unlink))).status)

        val record = KapeluszJson.decodeFromString(UserRecord.serializer(), store.get("users/bob.json")!!.content)
        assertEquals(UserRecord("bob", sessionsValidFrom = record.sessionsValidFrom), record, "his last band: only the account id stays")
        assertNull(store.get("users/bob-calendar.json"))
        assertTrue(KapeluszJson.decodeFromString(MeResponse.serializer(), call("GET", "/me", "bob").body!!).bands.isEmpty())
    }

    @Test
    fun deletingAMemberCancelsTheirInvites() {
        setup()
        val add = listOf(OpEnvelope("op10", "phone", 10, 1, AddMember("m-carol", "Carol")))
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(add)))
        val code = inviteCode("m-carol")
        val delete = listOf(OpEnvelope("op11", "phone", 11, 1, DeleteMember("m-carol")))
        assertEquals(200, call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(delete))).status)
        assertEquals(404, call("GET", "/invites/$code", "dan").status, "no old code comes back with an Undo")
    }

    /** A calendar link and a Google photo kept for [user], as signing in and subscribing leave them. */
    private fun subscribeAndGivePhoto(user: String) {
        call("POST", "/me/calendar", user, KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), CalendarSubscriptionChange()))
        val blob = store.get("users/$user.json")!!
        val record = KapeluszJson.decodeFromString(UserRecord.serializer(), blob.content)
        store.put("users/$user.json", KapeluszJson.encodeToString(UserRecord.serializer(), record.copy(pictureUrl = "https://lh3.googleusercontent.com/a/$user")), ifMatch = blob.etag)
        assertTrue(store.get("users/$user-calendar.json") != null)
    }
}
