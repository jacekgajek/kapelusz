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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// 2026-10-21 12:00 UTC
private class CalendarApi : ApiHarness(now = 1_792_584_000_000) {
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

    fun subscription(user: String, change: CalendarSubscriptionChange? = null): CalendarSubscription {
        val response = if (change == null) {
            call("GET", "/me/calendar", user)
        } else {
            call("POST", "/me/calendar", user, KapeluszJson.encodeToString(CalendarSubscriptionChange.serializer(), change))
        }
        withClue(response.body) { response.status shouldBe 200 }
        return KapeluszJson.decodeFromString(CalendarSubscription.serializer(), response.body!!)
    }

    fun feed(url: String) = call("GET", url.removePrefix("https://kapelusz.jacekgajek.com"), null)

    fun setup() {
        createBand()
        val ops = listOf(
            AddMember("m-bob", "Bob"),
            CreateEvent(
                "gig",
                "Club gig, Oakfield",
                LocalDate(2026, 10, 25),
                location = "Oakfield; Main St",
                participants = listOf(Participant("m-alice")),
                agenda = listOf(
                    AgendaTime("a", AgendaKind.ARRIVAL, time = LocalTime(17, 0)),
                    AgendaTime("e", label = "End", time = LocalTime(1, 30)),
                ),
            ),
            CreateEvent("allday", "Rehearsal", LocalDate(2026, 10, 28), participants = listOf(Participant("m-alice"))),
            CreateEvent(
                "one",
                "Radio",
                LocalDate(2026, 10, 30),
                location = "Klub Stodoła, Batorego 10, 02-591 Warszawa",
                participants = listOf(Participant("m-alice")),
                agenda = listOf(AgendaTime("s", AgendaKind.CONCERT_START, time = LocalTime(23, 30))),
            ),
            CreateEvent("bobs", "Bob's gig", LocalDate(2026, 10, 26), participants = listOf(Participant("m-bob"))),
            CreateEvent("old", "Long ago", LocalDate(2026, 6, 1), participants = listOf(Participant("m-alice"))),
        ).mapIndexed { i, op -> OpEnvelope("op$i", "phone", i + 1L, 1, op) }
        call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(ops)))
    }
}

class CalendarTest :
    ExpectSpec({

        expect("the feed has my gigs with their times") {
            with(CalendarApi()) {
                setup()
                val sub = subscription("alice")
                withClue("on the app's domain") {
                    sub.url shouldStartWith "https://kapelusz.jacekgajek.com/calendar/"
                    sub.url shouldEndWith ".ics"
                }
                val response = feed(sub.url)
                response.status shouldBe 200
                response.headers.getValue("content-type") shouldStartWith "text/calendar"
                val ics = response.body!!.replace("\r\n ", "")
                withClue(ics) { ics shouldContain "SUMMARY:Club gig\\, Oakfield" }
                ics shouldContain "LOCATION:Oakfield\\; Main St"
                withClue("earliest to latest; 01:30 is the next day") {
                    ics shouldContain "DTSTART:20261025T170000"
                    ics shouldContain "DTEND:20261026T013000"
                }
                withClue("no time: all day") {
                    ics shouldContain "DTSTART;VALUE=DATE:20261028"
                    ics shouldContain "DTEND;VALUE=DATE:20261029"
                }
                withClue("one time: an hour") {
                    ics shouldContain "DTSTART:20261030T233000"
                    ics shouldContain "DTEND:20261031T003000"
                }
                withClue("directions to the location, unescaped (a URI) and unfolded back whole") {
                    ics shouldContain
                        "URL:https://www.google.com/maps/dir/?api=1&destination=" +
                        "Klub%20Stodo%C5%82a%2C%20Batorego%2010%2C%2002-591%20Warszawa\r\n"
                }
                withClue("the event's own link in its description") {
                    ics.substringAfter("UID:one@kapelusz")
                        .substringAfter("DESCRIPTION:")
                        .substringBefore("\r\n") shouldContain "· https://kapelusz.jacekgajek.com/e/one"
                }
                withClue("not mine") { ics shouldNotContain "Bob's gig" }
                withClue("older than 3 months") { ics shouldNotContain "Long ago" }
                withClue("folded") { response.body!!.split("\r\n").all { it.encodeToByteArray().size <= 75 } shouldBe true }

                val all = subscription("alice", CalendarSubscriptionChange(includeAll = true))
                all.url shouldBe sub.url
                feed(all.url).body!!.replace("\r\n ", "") shouldContain "Bob's gig"
            }
        }

        expect("reset kills the old link") {
            with(CalendarApi()) {
                setup()
                val old = subscription("alice")
                val fresh = subscription("alice", CalendarSubscriptionChange(reset = true))
                fresh.url shouldNotBe old.url
                withClue("gone: passes CloudFront as it is") { feed(old.url).status shouldBe 410 }
                feed(fresh.url).status shouldBe 200
                call("GET", "/calendar/abcdefghijklmnopqrstuvwxyz.ics", null).status shouldBe 410
            }
        }

        expect("a removed member no longer sees the band") {
            with(CalendarApi()) {
                setup()
                val sub = subscription("alice")
                feed(sub.url).body!! shouldContain "Club gig"
                // Alice archived (removed) from the band by the system, as a leader would.
                val (band, etag) = store.get(StorageKeys.band("band1"))!!.let { KapeluszJson.decodeFromString(com.jacekgajek.kapelusz.model.Band.serializer(), it.content) to it.etag }
                val removed = band.copy(members = band.members.map { if (it.id == "m-alice") it.copy(archived = true) else it })
                store.put(StorageKeys.band("band1"), KapeluszJson.encodeToString(com.jacekgajek.kapelusz.model.Band.serializer(), removed), ifMatch = etag)
                feed(sub.url).body!! shouldNotContain "Club gig"
            }
        }

        expect("timestamps are UTC and equal times still last an hour") {
            with(CalendarApi()) {
                setup()
                val gig = CreateEvent(
                    "same",
                    "Same times",
                    LocalDate(2026, 11, 2),
                    participants = listOf(Participant("m-alice")),
                    agenda = listOf(
                        AgendaTime("x", label = "A", time = LocalTime(20, 0)),
                        AgendaTime("y", label = "B", time = LocalTime(20, 0)),
                    ),
                )
                call("POST", "/bands/band1/ops", "alice", KapeluszJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(OpEnvelope("same", "phone", 100, 1, gig)))))
                val ics = feed(subscription("alice").url).body!!.replace("\r\n ", "")
                withClue(ics) { Regex("DTSTAMP:\\d{8}T\\d{6}Z").containsMatchIn(ics) shouldBe true }
                ics shouldContain "DTSTART:20261102T200000\r\nDTEND:20261102T210000"
            }
        }

        expect("an odd event id cannot add lines to the feed") {
            // Ids are checked when created now; one from before must still not break out of its line.
            val band = com.jacekgajek.kapelusz.model.Band.create("b", "Night Owls", "PLN", "m", "Alice", "alice").let { b ->
                b.copy(events = listOf(com.jacekgajek.kapelusz.model.Event("x\r\nURL:https://evil", "Gig", LocalDate(2026, 10, 25), participants = listOf(Participant("m")))))
            }
            val ics = CalendarFeed.build(listOf(band), "alice", false, LocalDate(2026, 10, 21), "https://web", kotlinx.datetime.LocalDateTime(2026, 10, 21, 12, 0))
            withClue(ics) { ics.lines().any { it.startsWith("URL:https://evil") } shouldBe false }
        }

        // An artist's feed has only the events they play, even with "include all" on.
        expect("an artist's feed has only their gigs") {
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
            withClue(ics) {
                ics shouldContain "Carol plays"
                ics shouldNotContain "Alice alone"
            }
        }
    })
