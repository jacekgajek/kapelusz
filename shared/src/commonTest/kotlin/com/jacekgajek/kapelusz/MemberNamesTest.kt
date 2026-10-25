package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.PromoteGuest
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.CreateEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** ITERATION_5 §12: member names are unique, ignoring case, archived members included. */
class MemberNamesTest {
    @Test
    fun aNameInUseIsRefusedWhenAddingOrRenaming() {
        val scenario = Scenario("Alice", "Ola")
        assertEquals(RejectionReason.NAME_TAKEN, scenario.rejected(AddMember("x", " ola ")).reason)
        assertEquals(RejectionReason.NAME_TAKEN, scenario.rejected(RenameMember("alice", "OLA")).reason)
        scenario.apply(RenameMember("ola", "Ola"))
        scenario.apply(RenameMember("ola", "Ola K."))
        scenario.apply(SetMemberArchived("ola", true))
        assertEquals(RejectionReason.NAME_TAKEN, scenario.rejected(AddMember("y", "Ola K.")).reason, "archived members keep their names")
    }

    @Test
    fun aGuestBecomesAMemberUnderAFreeName() {
        val scenario = Scenario("Alice", "Ola")
        scenario.apply(CreateEvent("gig", "Gig", DATE, guests = listOf(Guest("g", "Ola")), participants = listOf(Participant("alice"), Participant("g"))))
        assertEquals(RejectionReason.NAME_TAKEN, scenario.rejected(PromoteGuest("gig", "g", "m-ola")).reason, "the guest's own name is taken")
        scenario.apply(PromoteGuest("gig", "g", "m-ola", "Ola K."))
        assertEquals("Ola K.", scenario.band.member("m-ola")!!.name)
        assertEquals(null, scenario.band.event("gig")!!.guest("g"))
        assertEquals(true, scenario.band.event("gig")!!.participant("m-ola") != null)
    }
}
