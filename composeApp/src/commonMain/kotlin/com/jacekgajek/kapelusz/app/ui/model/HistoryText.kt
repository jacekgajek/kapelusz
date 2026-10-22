package com.jacekgajek.kapelusz.app.ui.model

import com.jacekgajek.kapelusz.app.ui.screens.baseChange
import com.jacekgajek.kapelusz.app.ui.screens.isRedo
import androidx.compose.runtime.Composable
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.h_add_attachment
import com.jacekgajek.kapelusz.app.generated.h_add_cost
import com.jacekgajek.kapelusz.app.generated.h_redo
import com.jacekgajek.kapelusz.app.generated.h_undo
import com.jacekgajek.kapelusz.app.generated.h_add_fund_entry
import com.jacekgajek.kapelusz.app.generated.h_add_guest
import com.jacekgajek.kapelusz.app.generated.h_add_holding
import com.jacekgajek.kapelusz.app.generated.h_add_member
import com.jacekgajek.kapelusz.app.generated.h_add_participant
import com.jacekgajek.kapelusz.app.generated.h_archive_event
import com.jacekgajek.kapelusz.app.generated.h_archive_member
import com.jacekgajek.kapelusz.app.generated.h_create_event
import com.jacekgajek.kapelusz.app.generated.h_defer_refund
import com.jacekgajek.kapelusz.app.generated.h_delete_cost
import com.jacekgajek.kapelusz.app.generated.h_delete_event
import com.jacekgajek.kapelusz.app.generated.h_delete_member
import com.jacekgajek.kapelusz.app.generated.h_edit_cost
import com.jacekgajek.kapelusz.app.generated.h_edit_event
import com.jacekgajek.kapelusz.app.generated.h_edit_fund_entry
import com.jacekgajek.kapelusz.app.generated.h_edit_holding
import com.jacekgajek.kapelusz.app.generated.h_link_user
import com.jacekgajek.kapelusz.app.generated.h_move_member
import com.jacekgajek.kapelusz.app.generated.h_promote_guest
import com.jacekgajek.kapelusz.app.generated.h_record_transfer
import com.jacekgajek.kapelusz.app.generated.h_remove_attachment
import com.jacekgajek.kapelusz.app.generated.h_remove_fund_entry
import com.jacekgajek.kapelusz.app.generated.h_remove_holding
import com.jacekgajek.kapelusz.app.generated.h_remove_participant
import com.jacekgajek.kapelusz.app.generated.h_remove_transfer
import com.jacekgajek.kapelusz.app.generated.h_rename_band
import com.jacekgajek.kapelusz.app.generated.h_rename_guest
import com.jacekgajek.kapelusz.app.generated.h_rename_member
import com.jacekgajek.kapelusz.app.generated.h_restore_event
import com.jacekgajek.kapelusz.app.generated.h_restore_member
import com.jacekgajek.kapelusz.app.generated.h_set_fund_amount
import com.jacekgajek.kapelusz.app.generated.h_set_refund
import com.jacekgajek.kapelusz.app.generated.h_set_role
import com.jacekgajek.kapelusz.app.generated.h_set_treasurer
import com.jacekgajek.kapelusz.app.generated.h_disable_fund
import com.jacekgajek.kapelusz.app.generated.h_set_weight
import com.jacekgajek.kapelusz.app.generated.h_settle_all
import com.jacekgajek.kapelusz.app.generated.h_settle_party
import com.jacekgajek.kapelusz.app.generated.h_transfer_ownership
import com.jacekgajek.kapelusz.app.generated.h_unknown
import com.jacekgajek.kapelusz.app.generated.h_unlink_user
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddAttachment
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.MoveMember
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.PromoteGuest
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.RemoveAttachment
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.RemoveHolding
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.RemoveTransfer
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.RenameGuest
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.app.generated.h_set_agenda
import com.jacekgajek.kapelusz.app.generated.h_set_agenda_template
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.ops.TransferOwnership
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.ops.UpdateEvent
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.ops.UpdateHolding
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
        is DisableBandFund -> stringResource(Res.string.h_disable_fund)
        is TransferOwnership -> stringResource(Res.string.h_transfer_ownership, member(op.toMemberId))
        is CreateEvent -> stringResource(Res.string.h_create_event, op.name)
        is UpdateEvent -> stringResource(Res.string.h_edit_event, event(op.eventId))
        is SetEventArchived -> stringResource(if (op.archived) Res.string.h_archive_event else Res.string.h_restore_event, event(op.eventId))
        is SetEventAgenda -> stringResource(Res.string.h_set_agenda, event(op.eventId))
        is SetAgendaTemplate -> stringResource(Res.string.h_set_agenda_template)
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
        is Undo -> describe(baseChange(op), band).let { change ->
            if (isRedo(op)) stringResource(Res.string.h_redo, change) else stringResource(Res.string.h_undo, change)
        }
        is AddCost -> stringResource(Res.string.h_add_cost, money(op.amount), member(op.memberId))
        is UpdateCost -> stringResource(Res.string.h_edit_cost)
        is DeleteCost -> stringResource(Res.string.h_delete_cost)
        is AddAttachment -> stringResource(Res.string.h_add_attachment)
        is RemoveAttachment -> stringResource(Res.string.h_remove_attachment)
        else -> stringResource(Res.string.h_unknown)
    }
}
