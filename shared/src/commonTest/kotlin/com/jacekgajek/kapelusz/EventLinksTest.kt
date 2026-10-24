package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.EventLinks
import com.jacekgajek.kapelusz.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ITERATION_5 §10: links to an event. */
class EventLinksTest {
    @Test
    fun aLinkIsTheEventIdUnderE() {
        val id = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
        assertEquals("https://kapelusz.jacekgajek.com/e/$id", EventLinks.url("https://kapelusz.jacekgajek.com/", id))
        assertEquals(id, EventLinks.eventId("/e/$id"))
        assertEquals(id, EventLinks.eventId("/e/$id/"))
        assertNull(EventLinks.eventId("/join/ABC123"))
        assertNull(EventLinks.eventId("/e/../x"))
        assertNull(EventLinks.eventId("/e/"))
    }

    @Test
    fun anArtistOpensOnlyTheGigsTheyPlay() {
        val scenario = Scenario("Alice", "Bob", "Carol")
        scenario.userFor("bob", Role.ARTIST)
        scenario.userFor("carol", Role.MEMBER)
        scenario.event("played", "alice", "bob").event("other", "alice")
        val band = scenario.band
        assertTrue(EventLinks.canOpen(band, "user-bob", band.event("played")!!))
        assertFalse(EventLinks.canOpen(band, "user-bob", band.event("other")!!))
        assertTrue(EventLinks.canOpen(band, "user-carol", band.event("other")!!), "members see every event")
        assertFalse(EventLinks.canOpen(band, "user-mallory", band.event("other")!!))
    }
}
