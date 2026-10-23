package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.RejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** Viewers see everything but only watch: they take no part in events. */
class ViewerTest {

    @Test
    fun viewersAreNotAddedToEvents() {
        val scenario = Scenario("Alice", "Vera").event("gig", "Alice")
        scenario.userFor("vera", Role.VIEWER)
        assertEquals(RejectionReason.VIEWER_NOT_PARTICIPANT, scenario.rejected(AddParticipant("gig", "vera")).reason)
        assertEquals(
            RejectionReason.VIEWER_NOT_PARTICIPANT,
            scenario.rejected(CreateEvent("new", "new", DATE, participants = listOf(Participant("alice"), Participant("vera")))).reason,
        )
    }
}
