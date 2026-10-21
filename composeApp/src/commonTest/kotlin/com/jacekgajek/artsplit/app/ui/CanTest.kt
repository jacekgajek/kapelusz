package com.jacekgajek.artsplit.app.ui

import com.jacekgajek.artsplit.app.ui.model.Can
import com.jacekgajek.artsplit.auth.Actor
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.ApplyResult
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.Engine
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.Operation
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanTest {
    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.System, op, "op", 1) as ApplyResult.Applied).band
    }

    private val band = Band.create("b", "Band", "PLN", "owner", "Owner", "u-owner").apply(
        AddMember("lead", "Lead"), LinkUser("lead", "u-lead", Role.LEAD),
        AddMember("member", "Member"), LinkUser("member", "u-member", Role.MEMBER),
        AddMember("viewer", "Viewer"), LinkUser("viewer", "u-viewer", Role.VIEWER),
        CreateEvent("e", "Gig", LocalDate(2026, 9, 13), participants = listOf(Participant("lead"), Participant("member"))),
    )
    private val event = band.event("e")!!

    @Test
    fun answersFollowTheRoleMatrix() {
        val lead = Can(band, "u-lead")
        val member = Can(band, "u-member")
        val viewer = Can(band, "u-viewer")

        assertTrue(lead.createEvent())
        assertFalse(member.createEvent())
        assertTrue(lead.settleAll(event))
        assertFalse(member.settleAll(event))
        assertTrue(member.settleFor(event, "member"))
        assertFalse(member.settleFor(event, "lead"))

        assertEquals(listOf("lead", "member"), lead.holdingParties(event))
        assertEquals(listOf("member"), member.holdingParties(event))
        assertEquals(emptyList(), viewer.holdingParties(event))

        assertTrue(member.addCost())
        assertFalse(viewer.addCost())
        assertFalse(lead.manageRoster())
        assertTrue(Can(band, "u-owner").manageRoster())
        assertTrue(Can(band, "u-owner").transferOwnership())
    }
}
