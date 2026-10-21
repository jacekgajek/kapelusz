package com.jacekgajek.artsplit.app.ui.model

import androidx.compose.runtime.Composable
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.band_fund
import com.jacekgajek.artsplit.app.generated.h_add_attachment
import com.jacekgajek.artsplit.app.generated.h_add_cost
import com.jacekgajek.artsplit.app.generated.h_add_fund_entry
import com.jacekgajek.artsplit.app.generated.h_add_guest
import com.jacekgajek.artsplit.app.generated.h_add_holding
import com.jacekgajek.artsplit.app.generated.h_add_member
import com.jacekgajek.artsplit.app.generated.h_add_participant
import com.jacekgajek.artsplit.app.generated.h_archive_event
import com.jacekgajek.artsplit.app.generated.h_archive_member
import com.jacekgajek.artsplit.app.generated.h_create_event
import com.jacekgajek.artsplit.app.generated.h_defer_refund
import com.jacekgajek.artsplit.app.generated.h_delete_cost
import com.jacekgajek.artsplit.app.generated.h_delete_event
import com.jacekgajek.artsplit.app.generated.h_delete_member
import com.jacekgajek.artsplit.app.generated.h_edit_cost
import com.jacekgajek.artsplit.app.generated.h_edit_event
import com.jacekgajek.artsplit.app.generated.h_edit_fund_entry
import com.jacekgajek.artsplit.app.generated.h_edit_holding
import com.jacekgajek.artsplit.app.generated.h_link_user
import com.jacekgajek.artsplit.app.generated.h_move_member
import com.jacekgajek.artsplit.app.generated.h_promote_guest
import com.jacekgajek.artsplit.app.generated.h_record_transfer
import com.jacekgajek.artsplit.app.generated.h_remove_attachment
import com.jacekgajek.artsplit.app.generated.h_remove_fund_entry
import com.jacekgajek.artsplit.app.generated.h_remove_holding
import com.jacekgajek.artsplit.app.generated.h_remove_participant
import com.jacekgajek.artsplit.app.generated.h_remove_transfer
import com.jacekgajek.artsplit.app.generated.h_rename_band
import com.jacekgajek.artsplit.app.generated.h_rename_guest
import com.jacekgajek.artsplit.app.generated.h_rename_member
import com.jacekgajek.artsplit.app.generated.h_restore_event
import com.jacekgajek.artsplit.app.generated.h_restore_member
import com.jacekgajek.artsplit.app.generated.h_set_fund_amount
import com.jacekgajek.artsplit.app.generated.h_set_refund
import com.jacekgajek.artsplit.app.generated.h_set_role
import com.jacekgajek.artsplit.app.generated.h_set_treasurer
import com.jacekgajek.artsplit.app.generated.h_set_weight
import com.jacekgajek.artsplit.app.generated.h_settle_all
import com.jacekgajek.artsplit.app.generated.h_settle_party
import com.jacekgajek.artsplit.app.generated.h_transfer_ownership
import com.jacekgajek.artsplit.app.generated.h_unknown
import com.jacekgajek.artsplit.app.generated.h_unlink_user
import com.jacekgajek.artsplit.app.ui.components.label
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Id
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.ops.AddAttachment
import com.jacekgajek.artsplit.ops.AddCost
import com.jacekgajek.artsplit.ops.AddFundEntry
import com.jacekgajek.artsplit.ops.AddGuest
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.AddMember
import com.jacekgajek.artsplit.ops.AddParticipant
import com.jacekgajek.artsplit.ops.CreateEvent
import com.jacekgajek.artsplit.ops.DeleteCost
import com.jacekgajek.artsplit.ops.DeleteEvent
import com.jacekgajek.artsplit.ops.DeleteMember
import com.jacekgajek.artsplit.ops.LinkUser
import com.jacekgajek.artsplit.ops.MoveMember
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.PromoteGuest
import com.jacekgajek.artsplit.ops.RecordTransfer
import com.jacekgajek.artsplit.ops.RemoveAttachment
import com.jacekgajek.artsplit.ops.RemoveFundEntry
import com.jacekgajek.artsplit.ops.RemoveHolding
import com.jacekgajek.artsplit.ops.RemoveParticipant
import com.jacekgajek.artsplit.ops.RemoveTransfer
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.RenameGuest
import com.jacekgajek.artsplit.ops.RenameMember
import com.jacekgajek.artsplit.ops.SetEventArchived
import com.jacekgajek.artsplit.ops.SetFundAmount
import com.jacekgajek.artsplit.ops.SetMemberArchived
import com.jacekgajek.artsplit.ops.SetMemberRole
import com.jacekgajek.artsplit.ops.SetRefund
import com.jacekgajek.artsplit.ops.SetTreasurer
import com.jacekgajek.artsplit.ops.SetWeight
import com.jacekgajek.artsplit.ops.SettleSuggestions
import com.jacekgajek.artsplit.ops.TransferOwnership
import com.jacekgajek.artsplit.ops.UnlinkUser
import com.jacekgajek.artsplit.ops.UpdateCost
import com.jacekgajek.artsplit.ops.UpdateEvent
import com.jacekgajek.artsplit.ops.UpdateFundEntry
import com.jacekgajek.artsplit.ops.UpdateHolding
import org.jetbrains.compose.resources.stringResource

/**
 * One history line for an operation, e.g. "Recorded 250,00 zł from Dave to Alice at Oakfield Jazz Club". Names come
 * from the band as it is now; things deleted since then show as "?".
 */
@Composable
fun describe(op: Operation, band: Band): String {
    val lang = language
    val fund = stringResource(Res.string.band_fund)
    fun member(id: Id) = band.member(id)?.name ?: "?"
    fun event(id: Id) = band.event(id)?.name ?: "?"
    fun party(eventId: Id, id: Id) = partyName(band, band.event(eventId), id, fund).takeIf { it != id } ?: member(id)
    fun money(amount: Money) = formatMoney(amount, band.currency, lang)
    return when (op) {
        is RenameBand -> stringResource(Res.string.h_rename_band, op.name)
        is AddMember -> stringResource(Res.string.h_add_member, op.name)
        is RenameMember -> stringResource(Res.string.h_rename_member, op.name)
        is MoveMember -> stringResource(Res.string.h_move_member, member(op.memberId))
        is SetMemberArchived -> stringResource(if (op.archived) Res.string.h_archive_member else Res.string.h_restore_member, member(op.memberId))
        is DeleteMember -> stringResource(Res.string.h_delete_member)
        is SetMemberRole -> stringResource(Res.string.h_set_role, member(op.memberId), op.role.label())
        is LinkUser -> stringResource(Res.string.h_link_user, member(op.memberId), op.role.label())
        is UnlinkUser -> stringResource(Res.string.h_unlink_user, member(op.memberId))
        is SetTreasurer -> stringResource(Res.string.h_set_treasurer, member(op.memberId))
        is TransferOwnership -> stringResource(Res.string.h_transfer_ownership, member(op.toMemberId))
        is CreateEvent -> stringResource(Res.string.h_create_event, op.name)
        is UpdateEvent -> stringResource(Res.string.h_edit_event, event(op.eventId))
        is SetEventArchived -> stringResource(if (op.archived) Res.string.h_archive_event else Res.string.h_restore_event, event(op.eventId))
        is DeleteEvent -> stringResource(Res.string.h_delete_event)
        is AddParticipant -> stringResource(Res.string.h_add_participant, member(op.memberId), event(op.eventId))
        is AddGuest -> stringResource(Res.string.h_add_guest, op.name, event(op.eventId))
        is RenameGuest -> stringResource(Res.string.h_rename_guest, op.name, event(op.eventId))
        is PromoteGuest -> stringResource(Res.string.h_promote_guest, member(op.memberId))
        is SetWeight -> stringResource(Res.string.h_set_weight, party(op.eventId, op.partyId), "${op.weight}%", event(op.eventId))
        is RemoveParticipant -> stringResource(Res.string.h_remove_participant, party(op.eventId, op.partyId), event(op.eventId))
        is AddHolding -> stringResource(Res.string.h_add_holding, money(op.amount), party(op.eventId, op.partyId), event(op.eventId))
        is UpdateHolding -> stringResource(Res.string.h_edit_holding, event(op.eventId))
        is RemoveHolding -> stringResource(Res.string.h_remove_holding, event(op.eventId))
        is SetRefund -> if (op.amount.isZero) {
            stringResource(Res.string.h_defer_refund, event(op.eventId))
        } else {
            stringResource(Res.string.h_set_refund, money(op.amount), event(op.eventId))
        }
        is SetFundAmount -> stringResource(Res.string.h_set_fund_amount, money(op.amount), event(op.eventId))
        is AddFundEntry -> stringResource(Res.string.h_add_fund_entry, op.description, money(op.amount))
        is UpdateFundEntry -> stringResource(Res.string.h_edit_fund_entry)
        is RemoveFundEntry -> stringResource(Res.string.h_remove_fund_entry)
        is RecordTransfer -> stringResource(Res.string.h_record_transfer, money(op.amount), party(op.eventId, op.from), party(op.eventId, op.to), event(op.eventId))
        is SettleSuggestions -> op.partyId?.let { stringResource(Res.string.h_settle_party, party(op.eventId, it), event(op.eventId)) }
            ?: stringResource(Res.string.h_settle_all, event(op.eventId))
        is RemoveTransfer -> stringResource(Res.string.h_remove_transfer, event(op.eventId))
        is AddCost -> stringResource(Res.string.h_add_cost, money(op.amount), member(op.memberId))
        is UpdateCost -> stringResource(Res.string.h_edit_cost)
        is DeleteCost -> stringResource(Res.string.h_delete_cost)
        is AddAttachment -> stringResource(Res.string.h_add_attachment)
        is RemoveAttachment -> stringResource(Res.string.h_remove_attachment)
        else -> stringResource(Res.string.h_unknown)
    }
}
