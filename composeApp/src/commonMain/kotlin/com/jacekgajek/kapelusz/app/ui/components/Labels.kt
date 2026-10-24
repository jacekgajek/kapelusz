package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.Composable
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.reject_invalid_phone
import com.jacekgajek.kapelusz.app.generated.reject_cannot_settle_together
import com.jacekgajek.kapelusz.app.generated.category_accommodation
import com.jacekgajek.kapelusz.app.generated.category_equipment
import com.jacekgajek.kapelusz.app.generated.category_food
import com.jacekgajek.kapelusz.app.generated.category_other
import com.jacekgajek.kapelusz.app.generated.category_travel
import com.jacekgajek.kapelusz.app.generated.reject_already_participant
import com.jacekgajek.kapelusz.app.generated.reject_archived_event
import com.jacekgajek.kapelusz.app.generated.reject_archived_member
import com.jacekgajek.kapelusz.app.generated.reject_artist_holds_no_money
import com.jacekgajek.kapelusz.app.generated.reject_cannot_settle
import com.jacekgajek.kapelusz.app.generated.reject_cost_below_refunded
import com.jacekgajek.kapelusz.app.generated.reject_cost_partly_refunded
import com.jacekgajek.kapelusz.app.generated.reject_cost_refunded_from_fund
import com.jacekgajek.kapelusz.app.generated.reject_cost_refused
import com.jacekgajek.kapelusz.app.generated.reject_duplicate_id
import com.jacekgajek.kapelusz.app.generated.reject_empty_name
import com.jacekgajek.kapelusz.app.generated.reject_event_has_transfers
import com.jacekgajek.kapelusz.app.generated.reject_event_unsettled
import com.jacekgajek.kapelusz.app.generated.reject_holds_money
import com.jacekgajek.kapelusz.app.generated.reject_invalid_amount
import com.jacekgajek.kapelusz.app.generated.reject_invalid_input
import com.jacekgajek.kapelusz.app.generated.reject_invalid_share
import com.jacekgajek.kapelusz.app.generated.reject_invite_only
import com.jacekgajek.kapelusz.app.generated.reject_member_has_history
import com.jacekgajek.kapelusz.app.generated.reject_member_linked
import com.jacekgajek.kapelusz.app.generated.reject_no_treasurer
import com.jacekgajek.kapelusz.app.generated.reject_not_a_member
import com.jacekgajek.kapelusz.app.generated.reject_not_a_participant
import com.jacekgajek.kapelusz.app.generated.reject_not_found
import com.jacekgajek.kapelusz.app.generated.reject_not_refundable_here
import com.jacekgajek.kapelusz.app.generated.reject_owner_role_fixed
import com.jacekgajek.kapelusz.app.generated.reject_ownership_needs_admin
import com.jacekgajek.kapelusz.app.generated.reject_participant_has_money
import com.jacekgajek.kapelusz.app.generated.reject_participant_has_refunds
import com.jacekgajek.kapelusz.app.generated.reject_participant_has_transfers
import com.jacekgajek.kapelusz.app.generated.reject_refund_too_large
import com.jacekgajek.kapelusz.app.generated.reject_role_required
import com.jacekgajek.kapelusz.app.generated.reject_same_party
import com.jacekgajek.kapelusz.app.generated.reject_too_many_attachments
import com.jacekgajek.kapelusz.app.generated.reject_attachment_too_large
import com.jacekgajek.kapelusz.app.generated.reject_fund_not_empty
import com.jacekgajek.kapelusz.app.generated.reject_not_undoable
import com.jacekgajek.kapelusz.app.generated.reject_changed_since
import com.jacekgajek.kapelusz.app.generated.reject_limit_reached
import com.jacekgajek.kapelusz.app.generated.reject_storage_failed
import com.jacekgajek.kapelusz.app.generated.reject_treasurer_must_stay
import com.jacekgajek.kapelusz.app.generated.reject_user_already_member
import com.jacekgajek.kapelusz.app.generated.reject_viewer_not_participant
import com.jacekgajek.kapelusz.app.generated.reject_viewer_still_plays
import com.jacekgajek.kapelusz.app.generated.role_admin
import com.jacekgajek.kapelusz.app.generated.role_artist
import com.jacekgajek.kapelusz.app.generated.role_lead
import com.jacekgajek.kapelusz.app.generated.role_member
import com.jacekgajek.kapelusz.app.generated.role_owner
import com.jacekgajek.kapelusz.app.generated.role_viewer
import com.jacekgajek.kapelusz.app.generated.source_contract
import com.jacekgajek.kapelusz.app.generated.source_door
import com.jacekgajek.kapelusz.app.generated.source_hat
import com.jacekgajek.kapelusz.app.generated.source_merch
import com.jacekgajek.kapelusz.app.generated.source_other
import com.jacekgajek.kapelusz.app.generated.source_own_contribution
import com.jacekgajek.kapelusz.app.generated.source_sponsorship
import com.jacekgajek.kapelusz.app.generated.source_tickets_online
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.RejectionReason
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun IncomeSource.label(): String = stringResource(
    when (this) {
        IncomeSource.TICKETS_ONLINE -> Res.string.source_tickets_online
        IncomeSource.DOOR -> Res.string.source_door
        IncomeSource.HAT -> Res.string.source_hat
        IncomeSource.MERCH -> Res.string.source_merch
        IncomeSource.CONTRACT -> Res.string.source_contract
        IncomeSource.SPONSORSHIP -> Res.string.source_sponsorship
        IncomeSource.OWN_CONTRIBUTION -> Res.string.source_own_contribution
        IncomeSource.OTHER -> Res.string.source_other
    },
)

@Composable
fun CostCategory.label(): String = stringResource(
    when (this) {
        CostCategory.TRAVEL -> Res.string.category_travel
        CostCategory.ACCOMMODATION -> Res.string.category_accommodation
        CostCategory.EQUIPMENT -> Res.string.category_equipment
        CostCategory.FOOD -> Res.string.category_food
        CostCategory.OTHER -> Res.string.category_other
    },
)

@Composable
fun Role.label(): String = stringResource(
    when (this) {
        Role.VIEWER -> Res.string.role_viewer
        Role.ARTIST -> Res.string.role_artist
        Role.MEMBER -> Res.string.role_member
        Role.LEAD -> Res.string.role_lead
        Role.ADMIN -> Res.string.role_admin
        Role.OWNER -> Res.string.role_owner
    },
)

/** Localized text for a refused operation; keys are `reject_<reason>`. */
fun RejectionReason.labelRes(): StringResource = when (this) {
    RejectionReason.NOT_A_MEMBER -> Res.string.reject_not_a_member
    RejectionReason.ROLE_REQUIRED -> Res.string.reject_role_required
    RejectionReason.INVITE_ONLY -> Res.string.reject_invite_only
    RejectionReason.NOT_FOUND -> Res.string.reject_not_found
    RejectionReason.DUPLICATE_ID -> Res.string.reject_duplicate_id
    RejectionReason.INVALID_INPUT -> Res.string.reject_invalid_input
    RejectionReason.INVALID_PHONE -> Res.string.reject_invalid_phone
    RejectionReason.INVALID_AMOUNT -> Res.string.reject_invalid_amount
    RejectionReason.EMPTY_NAME -> Res.string.reject_empty_name
    RejectionReason.INVALID_SHARE -> Res.string.reject_invalid_share
    RejectionReason.NOT_A_PARTICIPANT -> Res.string.reject_not_a_participant
    RejectionReason.ALREADY_PARTICIPANT -> Res.string.reject_already_participant
    RejectionReason.SAME_PARTY -> Res.string.reject_same_party
    RejectionReason.NO_TREASURER -> Res.string.reject_no_treasurer
    RejectionReason.TREASURER_MUST_STAY -> Res.string.reject_treasurer_must_stay
    RejectionReason.ARCHIVED_MEMBER -> Res.string.reject_archived_member
    RejectionReason.ARCHIVED_EVENT -> Res.string.reject_archived_event
    RejectionReason.EVENT_UNSETTLED -> Res.string.reject_event_unsettled
    RejectionReason.ARTIST_HOLDS_NO_MONEY -> Res.string.reject_artist_holds_no_money
    RejectionReason.VIEWER_NOT_PARTICIPANT -> Res.string.reject_viewer_not_participant
    RejectionReason.VIEWER_STILL_PLAYS -> Res.string.reject_viewer_still_plays
    RejectionReason.HOLDS_MONEY -> Res.string.reject_holds_money
    RejectionReason.COST_REFUSED -> Res.string.reject_cost_refused
    RejectionReason.COST_PARTLY_REFUNDED -> Res.string.reject_cost_partly_refunded
    RejectionReason.OWNER_ROLE_FIXED -> Res.string.reject_owner_role_fixed
    RejectionReason.OWNERSHIP_NEEDS_ADMIN -> Res.string.reject_ownership_needs_admin
    RejectionReason.MEMBER_LINKED -> Res.string.reject_member_linked
    RejectionReason.USER_ALREADY_MEMBER -> Res.string.reject_user_already_member
    RejectionReason.MEMBER_HAS_HISTORY -> Res.string.reject_member_has_history
    RejectionReason.EVENT_HAS_TRANSFERS -> Res.string.reject_event_has_transfers
    RejectionReason.PARTICIPANT_HAS_MONEY -> Res.string.reject_participant_has_money
    RejectionReason.PARTICIPANT_HAS_TRANSFERS -> Res.string.reject_participant_has_transfers
    RejectionReason.PARTICIPANT_HAS_REFUNDS -> Res.string.reject_participant_has_refunds
    RejectionReason.NOT_REFUNDABLE_HERE -> Res.string.reject_not_refundable_here
    RejectionReason.REFUND_TOO_LARGE -> Res.string.reject_refund_too_large
    RejectionReason.COST_BELOW_REFUNDED -> Res.string.reject_cost_below_refunded
    RejectionReason.COST_REFUNDED_FROM_FUND -> Res.string.reject_cost_refunded_from_fund
    RejectionReason.CANNOT_SETTLE -> Res.string.reject_cannot_settle
    RejectionReason.TOO_MANY_ATTACHMENTS -> Res.string.reject_too_many_attachments
    RejectionReason.ATTACHMENT_TOO_LARGE -> Res.string.reject_attachment_too_large
    RejectionReason.FUND_NOT_EMPTY -> Res.string.reject_fund_not_empty
    RejectionReason.NOT_UNDOABLE -> Res.string.reject_not_undoable
    RejectionReason.CHANGED_SINCE -> Res.string.reject_changed_since
    RejectionReason.LIMIT_REACHED -> Res.string.reject_limit_reached
    RejectionReason.STORAGE_FAILED -> Res.string.reject_storage_failed
    RejectionReason.CANNOT_SETTLE_TOGETHER -> Res.string.reject_cannot_settle_together
}
