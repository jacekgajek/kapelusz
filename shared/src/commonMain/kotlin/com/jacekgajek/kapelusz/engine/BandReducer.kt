package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.BandOp
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.ScheduleBandDeletion
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetFundBankAccount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundDefault
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.rules.Artists
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.text.BlikPhone
import com.jacekgajek.kapelusz.view.BandView

/** The band itself: name, fund settings and payment details, agenda template, ownership, deletion. Pure state transitions and their validation; see [Reducer]. */
internal object BandReducer {
    fun apply(band: Band, op: BandOp, ctx: OpContext): Band = when (op) {
        is RenameBand -> band.copy(name = op.name.validName("Band name"))

        is SetTreasurer -> setTreasurer(band, op)

        is DisableBandFund -> {
            conflictIf(!BandFund.canDisable(band), RejectionReason.FUND_NOT_EMPTY) { "The band fund still holds or owes money" }
            // Its default goes, and percentages become the amounts they came to: one still following
            // income would owe money to a fund that is off, and changes to its event would be refused.
            band.copy(
                treasurerMemberId = null,
                fundDefault = null,
                // Where the money was kept, not where the holder chosen next keeps it.
                fundBlikPhone = null,
                fundBankAccount = null,
                events = band.events.map { if (it.fundPercent != null) it.copy(fundAmount = it.fund, fundPercent = null, updatedAt = ctx.timestamp) else it },
            )
        }

        is ScheduleBandDeletion -> {
            conflictIf(band.deletion != null, RejectionReason.CHANGED_SINCE) { "The band is already to be deleted" }
            band.copy(deletion = op.deletion)
        }

        // Two leaders keeping it at once: the second has nothing left to do.
        is CancelBandDeletion -> band.copy(deletion = null)

        is SetFundBlikPhone -> {
            invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "The band fund is off" }
            val phone = op.phone?.let { BlikPhone.normalize(it) }
            invalidIf(op.phone != null && phone == null, RejectionReason.INVALID_PHONE) { "Not a phone number" }
            band.copy(fundBlikPhone = phone)
        }

        is SetFundBankAccount -> {
            invalidIf(band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "The band fund is off" }
            band.copy(fundBankAccount = account(op.account))
        }

        is TransferOwnership -> transferOwnership(band, op)

        is SetAgendaTemplate -> {
            invalidIf(op.items.size > AgendaTime.MAX_TIMES, RejectionReason.INVALID_INPUT) { "At most ${AgendaTime.MAX_TIMES} agenda times" }
            op.items.forEach { validAgendaLabel(it.kind, it.label) }
            val items = op.items.map { it.copy(label = it.label?.trim()) }
            // Exactly the built-in times is stored as "the defaults", so built-in times added later apply too.
            band.copy(agendaTemplate = items.takeUnless { it == AgendaKind.entries.map { kind -> AgendaTemplateItem(kind) } })
        }

        is SetFundDefault -> setFundDefault(band, op, ctx)
    }

    private fun setTreasurer(band: Band, op: SetTreasurer): Band {
        val member = band.member(op.memberId).orNotFound { "Member ${op.memberId}" }
        invalidIf(member.archived, RejectionReason.ARCHIVED_MEMBER) { "An archived member cannot hold the band fund" }
        invalidIf(Artists.isArtist(band, member.id), RejectionReason.ARTIST_HOLDS_NO_MONEY) { "An artist cannot hold the band fund" }
        // The holder records the fund's transfers, which a viewer may not.
        invalidIf(member.role == Role.VIEWER, RejectionReason.VIEWER_NOT_PARTICIPANT) { "A viewer cannot hold the band fund" }
        return band.copy(treasurerMemberId = member.id)
    }

    private fun transferOwnership(band: Band, op: TransferOwnership): Band {
        val target = band.member(op.toMemberId).orNotFound { "Member ${op.toMemberId}" }
        invalidIf(target.userId == null || target.role != Role.LEAD, RejectionReason.OWNERSHIP_NEEDS_LEADER) { "Ownership can only go to a linked leader" }
        // An archived owner could do nothing at all, the band included.
        invalidIf(target.archived, RejectionReason.ARCHIVED_MEMBER) { "Ownership cannot go to an archived member" }
        val currentOwner = band.owner
        return band.copy(
            members = band.members.map {
                when (it.id) {
                    target.id -> it.copy(role = Role.OWNER)

                    // The previous owner keeps every right but ownership.
                    currentOwner?.id -> it.copy(role = Role.LEAD)

                    else -> it
                }
            },
        )
    }

    private fun setFundDefault(band: Band, op: SetFundDefault, ctx: OpContext): Band {
        val cut = op.cut?.let(::validFundCut)
        invalidIf(cut != null && band.treasurerMemberId == null, RejectionReason.NO_TREASURER) { "Choose a treasurer before using the band fund" }
        var result = band.copy(fundDefault = cut)
        // As with a member's default share: events changed since the user chose them (settled, archived, gone) stay as they are.
        val view = BandView(band)
        for (eventId in op.events.distinct()) {
            band.event(eventId)?.takeIf { BandFund.canApplyDefault(view, it) } ?: continue
            result = result.updateEvent(eventId) {
                it.copy(fundAmount = cut?.amount ?: Money.ZERO, fundPercent = cut?.percent, updatedAt = ctx.timestamp)
            }
        }
        return result
    }
}
