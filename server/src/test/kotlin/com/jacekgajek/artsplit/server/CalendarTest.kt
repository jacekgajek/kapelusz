package com.jacekgajek.artsplit.server

import com.jacekgajek.artsplit.api.CalendarSubscription
import com.jacekgajek.artsplit.api.CalendarSubscriptionChange
import com.jacekgajek.artsplit.api.CreateBandRequest
import com.jacekgajek.artsplit.api.PushRequest
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.ArtSplitJson
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.OpEnvelope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CalendarTest {
    private val store = InMemoryBlobStore()
    // 2026-10-21 12:00 UTC
    private val api = Api(store, { token -> VerifiedUser(token, null, null) }, clock = { 1_792_584_000_000 })

    private fun call(method: String, path: String, user: String?, body: String? = null) = api.handle(
        HttpRequest(method, path, listOfNotNull("host" to "api.test", user?.let { "authorization" to "Bearer $it" }).toMap(), body),
    )

    private fun subscription(user: String, change: CalendarSubscriptionChange? = null): CalendarSubscription {
        val response = if (change == null) call("GET", "/me/calendar", user)
        else call("POST", "/me/calendar", user, ArtSplitJson.encodeToString(CalendarSubscriptionChange.serializer(), change))
        assertEquals(200, response.status, response.body)
        return ArtSplitJson.decodeFromString(CalendarSubscription.serializer(), response.body!!)
    }

    private fun feed(url: String) = call("GET", url.removePrefix("https://api.test"), null)

    private fun setup() {
        call("POST", "/bands", "alice", ArtSplitJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        val ops = listOf(
            AddMember("m-bob", "Bob"),
            CreateEvent("gig", "Club gig, Oakfield", LocalDate(2026, 10, 25), location = "Oakfield; Main St",
                participants = listOf(Participant("m-alice")),
                agenda = listOf(AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0)), AgendaTime("e", label = "End", time = LocalTime(1, 30)))),
            CreateEvent("allday", "Rehearsal", LocalDate(2026, 10, 28), participants = listOf(Participant("m-alice"))),
            CreateEvent("one", "Radio", LocalDate(2026, 10, 30), participants = listOf(Participant("m-alice")), agenda = listOf(AgendaTime("s", AgendaKind.CONCERT_START, time = LocalTime(23, 30)))),
            CreateEvent("bobs", "Bob's gig", LocalDate(2026, 10, 26), participants = listOf(Participant("m-bob"))),
            CreateEvent("old", "Long ago", LocalDate(2026, 6, 1), participants = listOf(Participant("m-alice"))),
        ).mapIndexed { i, op -> OpEnvelope("op$i", "phone", i + 1L, 1, op) }
        call("POST", "/bands/band1/ops", "alice", ArtSplitJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
    }

    @Test
    fun theFeedHasMyGigsWithTheirTimes() {
        setup()
        val sub = subscription("alice")
        assertTrue(sub.url.startsWith("https://api.test/calendar/") && sub.url.endsWith(".ics"))
        val response = feed(sub.url)
        assertEquals(200, response.status)
        assertTrue(response.headers.getValue("content-type").startsWith("text/calendar"))
        val ics = response.body!!.replace("\r\n ", "")
        assertTrue("SUMMARY:Club gig\\, Oakfield" in ics, ics)
        assertTrue("LOCATION:Oakfield\\; Main St" in ics)
        assertTrue("DTSTART:20261025T170000" in ics && "DTEND:20261026T013000" in ics, "earliest to latest; 01:30 is the next day")
        assertTrue("DTSTART;VALUE=DATE:20261028" in ics && "DTEND;VALUE=DATE:20261029" in ics, "no time: all day")
        assertTrue("DTSTART:20261030T233000" in ics && "DTEND:20261031T003000" in ics, "one time: an hour")
        assertFalse("Bob's gig" in ics, "not mine")
        assertFalse("Long ago" in ics, "older than 3 months")
        assertTrue(response.body!!.split("\r\n").all { it.encodeToByteArray().size <= 75 }, "folded")

        val all = subscription("alice", CalendarSubscriptionChange(includeAll = true))
        assertEquals(sub.url, all.url)
        assertTrue("Bob's gig" in feed(all.url).body!!.replace("\r\n ", ""))
    }

    @Test
    fun resetKillsTheOldLink() {
        setup()
        val old = subscription("alice")
        val fresh = subscription("alice", CalendarSubscriptionChange(reset = true))
        assertNotEquals(old.url, fresh.url)
        assertEquals(404, feed(old.url).status)
        assertEquals(200, feed(fresh.url).status)
        assertEquals(404, call("GET", "/calendar/abcdefghijklmnopqrstuvwxyz.ics", null).status)
    }
}
