package com.jacekgajek.kapelusz.rules

import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class CapabilitiesTest {
    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
    }

    private val band = Band.create("b", "Band", "PLN", "owner", "Owner", "u-owner").apply(
        AddMember("lead", "Lead"),
        LinkUser("lead", "u-lead", Role.LEAD),
        AddMember("member", "Member"),
        LinkUser("member", "u-member", Role.MEMBER),
        AddMember("viewer", "Viewer"),
        LinkUser("viewer", "u-viewer", Role.VIEWER),
        CreateEvent("e", "Gig", LocalDate(2026, 9, 13), participants = listOf(Participant("lead"), Participant("member"))),
    )
    private val event = band.event("e")!!

    @Test
    fun answersFollowTheRoleMatrix() {
        val lead = Capabilities(band, "u-lead")
        val member = Capabilities(band, "u-member")
        val viewer = Capabilities(band, "u-viewer")

        assertTrue(lead.createEvent())
        assertFalse(member.createEvent())
        assertTrue(lead.settleAll(event))
        assertFalse(member.settleAll(event))

        assertEquals(listOf("lead", "member"), lead.holdingParties(event))
        assertEquals(listOf("member"), member.holdingParties(event))
        assertEquals(emptyList(), viewer.holdingParties(event))

        assertTrue(member.addCost())
        assertFalse(viewer.addCost())
        assertTrue(lead.manageRoster(), "leaders manage the band's members (I5 §11)")
        assertFalse(member.manageRoster())
        assertFalse(lead.transferOwnership())
        assertTrue(Capabilities(band, "u-owner").transferOwnership())
    }

    /** "Add cost" in an event: a lead adds for any participant, a member only when taking part themselves. */
    @Test
    fun addingACostInAnEventNeedsSomeoneInIt() {
        val other = band.apply(CreateEvent("o", "Other gig", LocalDate(2026, 9, 14), participants = listOf(Participant("owner"))))
        assertTrue(Capabilities(other, "u-lead").addCostIn(other.event("e")!!))
        assertTrue(Capabilities(other, "u-member").addCostIn(other.event("e")!!))
        assertTrue(Capabilities(other, "u-lead").addCostIn(other.event("o")!!), "a lead adds it for the owner")
        assertFalse(Capabilities(other, "u-member").addCostIn(other.event("o")!!), "not in it")
        assertFalse(Capabilities(other, "u-viewer").addCostIn(other.event("e")!!))
    }
}
