package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.text.EventLinks
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Links to an event. */
class EventLinksTest :
    ExpectSpec({
        expect("a link is the event id under /e") {
            val id = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
            EventLinks.url("https://kapelusz.jacekgajek.com/", id) shouldBe "https://kapelusz.jacekgajek.com/e/$id"
            EventLinks.eventId("/e/$id") shouldBe id
            EventLinks.eventId("/e/$id/") shouldBe id
            EventLinks.eventId("/join/ABC123").shouldBeNull()
            EventLinks.eventId("/e/../x").shouldBeNull()
            EventLinks.eventId("/e/").shouldBeNull()
        }

        expect("an artist opens only the gigs they play") {
            val scenario = Scenario("Alice", "Bob", "Carol")
            scenario.userFor("bob", Role.ARTIST)
            scenario.userFor("carol", Role.MEMBER)
            scenario.event("played", "alice", "bob").event("other", "alice")
            val band = scenario.band
            EventLinks.canOpen(band, "user-bob", band.event("played")!!) shouldBe true
            EventLinks.canOpen(band, "user-bob", band.event("other")!!) shouldBe false
            withClue("members see every event") { EventLinks.canOpen(band, "user-carol", band.event("other")!!) shouldBe true }
            EventLinks.canOpen(band, "user-mallory", band.event("other")!!) shouldBe false
        }
    })
