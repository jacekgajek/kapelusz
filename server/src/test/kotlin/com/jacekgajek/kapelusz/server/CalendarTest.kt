package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.CreateBandRequest
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.KapeluszJson
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.OpEnvelope
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
        else call("POST", "/me/calendar", user, KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), change))
        assertEquals(200, response.status, response.body)
        return KapeluszJson.decodeFromString(CalendarSubscription.serializer(), response.body!!)
    }

    private fun feed(url: String) = call("GET", url.removePrefix("https://api.test"), null)

    private fun setup() {
        call("POST", "/bands", "alice", KapeluszJson.encodeToString(CreateBandRequest.serializer(), CreateBandRequest("band1", "Night Owls", "PLN", "m-alice", "Alice")))
        val ops = listOf(
            AddMember("m-bob", "Bob"),
            CreateEvent("gig", "Club gig, Oakfield", LocalDate(2026, 10, 25), location = "Oakfield; Main St",
                participants = listOf(Participant("m-alice")),
                agenda = listOf(AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0)), AgendaTime("e", label = "End", time = LocalTime(1, 30)))),
            CreateEvent("allday", "Rehearsal", LocalDate(2026, 10, 28), participants = listOf(Participant("m-alice"))),
            CreateEvent("one", "Radio", LocalDate(2026, 10, 30), mapUrl = "https://www.google.com/maps/place/Klub+Stodo%C5%82a/@52.2117,21.0105,17z/data=!3m1!4b1!4m6!3m5!1s0x471ecd2a:0x6b8e!8m2!3d52.21!4d21.01", participants = listOf(Participant("m-alice")), agenda = listOf(AgendaTime("s", AgendaKind.CONCERT_START, time = LocalTime(23, 30)))),
            CreateEvent("bobs", "Bob's gig", LocalDate(2026, 10, 26), participants = listOf(Participant("m-bob"))),
            CreateEvent("old", "Long ago", LocalDate(2026, 6, 1), participants = listOf(Participant("m-alice"))),
        ).mapIndexed { i, op -> OpEnvelope("op$i", "phone", i + 1L, 1, op) }
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
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
        assertTrue("URL:https://www.google.com/maps/place/Klub+Stodo%C5%82a/@52.2117,21.0105,17z/data=!3m1!4b1!4m6!3m5!1s0x471ecd2a:0x6b8e!8m2!3d52.21!4d21.01\r\n" in ics, "the Maps link, unescaped (a URI) and unfolded back whole")
        assertTrue("https://www.google.com/maps/place/Klub+Stodo%C5%82a/@52.2117,21.0105,17z/data=!3m1!4b1!4m6!3m5!1s0x471ecd2a:0x6b8e!8m2!3d52.21!4d21.01" in ics.substringAfter("UID:one@kapelusz").substringAfter("DESCRIPTION:"), "the link in the description too")
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

    @Test
    fun aRemovedMemberNoLongerSeesTheBand() {
        setup()
        val sub = subscription("alice")
        assertTrue("Club gig" in feed(sub.url).body!!)
        // Alice archived (removed) from the band by the system, as an admin would.
        val (band, etag) = store.get(Api.bandKey("band1"))!!.let { KapeluszJson.decodeFromString(com.jacekgajek.kapelusz.model.Band.serializer(), it.content) to it.etag }
        val removed = band.copy(members = band.members.map { if (it.id == "m-alice") it.copy(archived = true) else it })
        store.put(Api.bandKey("band1"), KapeluszJson.encodeToString(com.jacekgajek.kapelusz.model.Band.serializer(), removed), ifMatch = etag)
        assertFalse("Club gig" in feed(sub.url).body!!)
    }

    @Test
    fun timestampsAreUtcAndEqualTimesStillLastAnHour() {
        setup()
        val gig = CreateEvent("same", "Same times", LocalDate(2026, 11, 2), participants = listOf(Participant("m-alice")),
            agenda = listOf(AgendaTime("x", label = "A", time = LocalTime(20, 0)), AgendaTime("y", label = "B", time = LocalTime(20, 0))))
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("same", "phone", 100, 1, gig)))))
        val ics = feed(subscription("alice").url).body!!.replace("\r\n ", "")
        assertTrue(Regex("DTSTAMP:\\d{8}T\\d{6}Z").containsMatchIn(ics), ics)
        assertTrue("DTSTART:20261102T200000\r\nDTEND:20261102T210000" in ics)
    }

    @Test
    fun anOddEventIdCannotAddLinesToTheFeed() {
        // Ids are checked when created now; one from before must still not break out of its line.
        val band = com.jacekgajek.kapelusz.model.Band.create("b", "Night Owls", "PLN", "m", "Alice", "alice").let { b ->
            b.copy(events = listOf(com.jacekgajek.kapelusz.model.Event("x\r\nURL:https://evil", "Gig", LocalDate(2026, 10, 25), participants = listOf(Participant("m")))))
        }
        val ics = CalendarFeed.build(listOf(band), "alice", false, LocalDate(2026, 10, 21), "https://web", kotlinx.datetime.LocalDateTime(2026, 10, 21, 12, 0))
        assertFalse(ics.lines().any { it.startsWith("URL:https://evil") }, ics)
    }
}
