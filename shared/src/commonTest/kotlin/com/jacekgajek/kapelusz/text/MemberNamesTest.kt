package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import kotlin.test.Test
import kotlin.test.assertEquals

/** Member names are unique, ignoring case, archived members included. */
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
}
