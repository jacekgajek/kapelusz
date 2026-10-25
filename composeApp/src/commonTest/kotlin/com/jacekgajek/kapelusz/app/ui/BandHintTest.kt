package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.screens.BandHint
import com.jacekgajek.kapelusz.app.ui.screens.bandHint
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ITERATION_5 §11: what a band still lacks, for its leaders. */
class BandHintTest {
    private val solo = Band.create(id = "b", name = "Band", currency = "PLN", ownerMemberId = "o", ownerName = "Olga", ownerUserId = "u-o")
    private val owner = solo.member("o")!!

    @Test
    fun aBandWithOnlyItsOwnerHasNoMembersAndThatCantBeHidden() {
        assertEquals(BandHint.NO_MEMBERS, bandHint(solo, owner, hiddenNoApp = true))
    }

    @Test
    fun membersWithoutTheAppGetTheInviteHintUntilHiddenOrLinked() {
        val withMembers = solo.copy(members = solo.members + owner.copy(id = "b1", name = "Bob", userId = null, role = Role.MEMBER))
        assertEquals(BandHint.NO_APP, bandHint(withMembers, owner, hiddenNoApp = false))
        assertNull(bandHint(withMembers, owner, hiddenNoApp = true))
        val linked = withMembers.copy(members = withMembers.members.map { if (it.id == "b1") it.copy(userId = "u-b") else it })
        assertNull(bandHint(linked, owner, hiddenNoApp = false))
        val archivedOnly = withMembers.copy(members = withMembers.members.map { if (it.id == "b1") it.copy(archived = true) else it })
        assertEquals(BandHint.NO_MEMBERS, bandHint(archivedOnly, owner, hiddenNoApp = false), "archived members don't count")
    }

    @Test
    fun onlyLeadersSeeHints() {
        val withMembers = solo.copy(members = solo.members + owner.copy(id = "b1", name = "Bob", userId = "u-b", role = Role.MEMBER))
        assertNull(bandHint(withMembers, withMembers.member("b1"), hiddenNoApp = false))
        assertNull(bandHint(solo, null, hiddenNoApp = false))
    }
}
