package com.jacekgajek.kapelusz.server

import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.PushRequest
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.server.calendar.CalendarFeed
import com.jacekgajek.kapelusz.server.store.StorageKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// 2026-10-21 12:00 UTC
class CalendarTest : ApiHarness(now = 1_792_584_000_000) {
    /** On the app's host, as CloudFront forwards it. */
    override fun call(
        method: String,
        path: String,
        user: String?,
        body: String?,
        query: Map<String, String>,
        headers: Map<String, String>,
        on: Api,
    ): HttpResponse = super.call(method, path, user, body, query, headers + ("host" to "api.test"), on)

    private fun subscription(user: String, change: CalendarSubscriptionChange? = null): CalendarSubscription {
        val response = if (change == null) {
            call("GET", "/me/calendar", user)
        } else {
            call("POST", "/me/calendar", user, KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), change))
        }
        assertEquals(200, response.status, response.body)
        return KapeluszJson.decodeFromString(CalendarSubscription.serializer(), response.body!!)
    }

    private fun feed(url: String) = call("GET", url.removePrefix("https://kapelusz.jacekgajek.com"), null)

    private fun setup() {
        createBand()
        val ops = listOf(
            AddMember("m-bob", "Bob"),
            CreateEvent(
                "gig",
                "Club gig, Oakfield",
                LocalDate(2026, 10, 25),
                location = "Oakfield; Main St",
                participants = listOf(Participant("m-alice")),
                agenda = listOf(AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0)), AgendaTime("e", label = "End", time = LocalTime(1, 30))),
            ),
            CreateEvent("allday", "Rehearsal", LocalDate(2026, 10, 28), participants = listOf(Participant("m-alice"))),
            CreateEvent("one", "Radio", LocalDate(2026, 10, 30), location = "Klub Stodoła, Batorego 10, 02-591 Warszawa", participants = listOf(Participant("m-alice")), agenda = listOf(AgendaTime("s", AgendaKind.CONCERT_START, time = LocalTime(23, 30)))),
            CreateEvent("bobs", "Bob's gig", LocalDate(2026, 10, 26), participants = listOf(Participant("m-bob"))),
            CreateEvent("old", "Long ago", LocalDate(2026, 6, 1), participants = listOf(Participant("m-alice"))),
        ).mapIndexed { i, op -> OpEnvelope("op$i", "phone", i + 1L, 1, op) }
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
    }

    @Test
    fun theFeedHasMyGigsWithTheirTimes() {
        setup()
        val sub = subscription("alice")
        assertTrue(sub.url.startsWith("https://kapelusz.jacekgajek.com/calendar/") && sub.url.endsWith(".ics"), "on the app's domain")
        val response = feed(sub.url)
        assertEquals(200, response.status)
        assertTrue(response.headers.getValue("content-type").startsWith("text/calendar"))
        val ics = response.body!!.replace("\r\n ", "")
        assertTrue("SUMMARY:Club gig\\, Oakfield" in ics, ics)
        assertTrue("LOCATION:Oakfield\\; Main St" in ics)
        assertTrue("DTSTART:20261025T170000" in ics && "DTEND:20261026T013000" in ics, "earliest to latest; 01:30 is the next day")
        assertTrue("DTSTART;VALUE=DATE:20261028" in ics && "DTEND;VALUE=DATE:20261029" in ics, "no time: all day")
        assertTrue("DTSTART:20261030T233000" in ics && "DTEND:20261031T003000" in ics, "one time: an hour")
        assertTrue("URL:https://www.google.com/maps/dir/?api=1&destination=Klub%20Stodo%C5%82a%2C%20Batorego%2010%2C%2002-591%20Warszawa\r\n" in ics, "directions to the location, unescaped (a URI) and unfolded back whole")
        assertTrue("· https://kapelusz.jacekgajek.com/e/one" in ics.substringAfter("UID:one@kapelusz").substringAfter("DESCRIPTION:").substringBefore("\r\n"), "the event's own link in its description")
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
        assertEquals(410, feed(old.url).status, "gone: passes CloudFront as it is")
        assertEquals(200, feed(fresh.url).status)
        assertEquals(410, call("GET", "/calendar/abcdefghijklmnopqrstuvwxyz.ics", null).status)
    }

    @Test
    fun aRemovedMemberNoLongerSeesTheBand() {
        setup()
        val sub = subscription("alice")
        assertTrue("Club gig" in feed(sub.url).body!!)
        // Alice archived (removed) from the band by the system, as a leader would.
        val (band, etag) = store.get(StorageKeys.band("band1"))!!.let { KapeluszJson.decodeFromString(com.jacekgajek.kapelusz.model.Band.serializer(), it.content) to it.etag }
        val removed = band.copy(members = band.members.map { if (it.id == "m-alice") it.copy(archived = true) else it })
        store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(com.jacekgajek.kapelusz.model.Band.serializer(), removed), ifMatch = etag)
        assertFalse("Club gig" in feed(sub.url).body!!)
    }

    @Test
    fun timestampsAreUtcAndEqualTimesStillLastAnHour() {
        setup()
        val gig = CreateEvent(
            "same",
            "Same times",
            LocalDate(2026, 11, 2),
            participants = listOf(Participant("m-alice")),
            agenda = listOf(AgendaTime("x", label = "A", time = LocalTime(20, 0)), AgendaTime("y", label = "B", time = LocalTime(20, 0))),
        )
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

    /** An artist's feed has only the events they play, even with "include all" on. */
    @Test
    fun anArtistsFeedHasOnlyTheirGigs() {
        val band = com.jacekgajek.kapelusz.model.Band.create("b", "Night Owls", "PLN", "m", "Alice", "alice").let { b ->
            b.copy(
                members = b.members + com.jacekgajek.kapelusz.model.Member("c", "Carol", userId = "carol", role = com.jacekgajek.kapelusz.model.Role.ARTIST),
                events = listOf(
                    com.jacekgajek.kapelusz.model.Event("mine", "Carol plays", LocalDate(2026, 10, 25), participants = listOf(Participant("c"))),
                    com.jacekgajek.kapelusz.model.Event("other", "Alice alone", LocalDate(2026, 10, 26), participants = listOf(Participant("m"))),
                ),
            )
        }
        val ics = CalendarFeed.build(listOf(band), "carol", true, LocalDate(2026, 10, 21), "https://web", kotlinx.datetime.LocalDateTime(2026, 10, 21, 12, 0))
        assertTrue("Carol plays" in ics, ics)
        assertFalse("Alice alone" in ics, ics)
    }
}
