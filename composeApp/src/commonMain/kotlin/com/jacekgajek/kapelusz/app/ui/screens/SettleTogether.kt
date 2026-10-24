package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.settle_together_accept
import com.jacekgajek.kapelusz.app.generated.settle_together_cancel
import com.jacekgajek.kapelusz.app.generated.settle_together_decline
import com.jacekgajek.kapelusz.app.generated.settle_together_ends
import com.jacekgajek.kapelusz.app.generated.settle_together_hint
import com.jacekgajek.kapelusz.app.generated.settle_together_invite
import com.jacekgajek.kapelusz.app.generated.settle_together_invited
import com.jacekgajek.kapelusz.app.generated.settle_together_pending_other
import com.jacekgajek.kapelusz.app.generated.settle_together_stop
import com.jacekgajek.kapelusz.app.generated.settle_together_stop_text
import com.jacekgajek.kapelusz.app.generated.settle_together_title
import com.jacekgajek.kapelusz.app.generated.settle_together_waiting
import com.jacekgajek.kapelusz.app.generated.settle_together_with
import com.jacekgajek.kapelusz.app.generated.settle_together_you_with
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.CancelSettleTogether
import com.jacekgajek.kapelusz.ops.DeclineSettleTogether
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.StopSettlingTogether
import org.jetbrains.compose.resources.stringResource

/**
 * Settling together (ITERATION_5 §7) in [member]'s dialog, as seen by [me]: invite them, the invitation waiting either
 * way, their pair, or yours with them. Shown where [hasSettleTogether].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettleTogetherSection(band: Band, me: Member, member: Member, actions: Actions) {
    val partner = member.settlesWith?.let { band.member(it) }
    val inviter = SettleTogether.inviterOf(band, member.id)
    var stopping by remember(member.id) { mutableStateOf<Member?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.settle_together_title), style = MaterialTheme.typography.labelLarge)
        when {
            // Your own pair, from your row or your partner's; artists can't end it.
            partner != null && (member.id == me.id || partner.id == me.id) -> {
                val other = if (member.id == me.id) partner else member
                Text(stringResource(Res.string.settle_together_you_with, other.name))
                if (me.role.atLeast(Role.MEMBER)) OutlinedButton(onClick = { stopping = other }) { Text(stringResource(Res.string.settle_together_stop)) }
            }
            partner != null -> Text(stringResource(Res.string.settle_together_with, partner.name))
            // You invited them, or you're looking at yourself with an invitation sent.
            me.invitedToSettle != null && (member.id == me.id || me.invitedToSettle == member.id) -> {
                val invitee = band.member(me.invitedToSettle!!)
                Text(stringResource(Res.string.settle_together_waiting, invitee?.name ?: "?"))
                OutlinedButton(onClick = { actions.run(CancelSettleTogether(me.id, me.invitedToSettle!!)) }) {
                    Text(stringResource(Res.string.settle_together_cancel))
                }
            }
            // They invited you, or you're looking at yourself, invited.
            (member.id == me.id && SettleTogether.inviterOf(band, me.id) != null) || member.invitedToSettle == me.id -> {
                val from = SettleTogether.inviterOf(band, me.id)!!
                InvitationAnswer(from, me, actions)
            }
            member.invitedToSettle != null || inviter != null -> Text(stringResource(Res.string.settle_together_pending_other))
            SettleTogether.mayInvite(band, me, member) -> {
                Text(stringResource(Res.string.settle_together_hint), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { actions.run(InviteToSettleTogether(me.id, member.id)) }) {
                    Text(stringResource(Res.string.settle_together_invite))
                }
            }
        }
    }
    stopping?.let { other ->
        ConfirmDialog(
            title = stringResource(Res.string.settle_together_stop),
            text = stringResource(Res.string.settle_together_stop_text, other.name, SettleTogether.openEventsTogether(band, me.id, other.id).toString()),
            confirmLabel = stringResource(Res.string.settle_together_stop),
            onConfirm = { actions.run(StopSettlingTogether(me.id, other.id)) },
            onDismiss = { stopping = null },
        )
    }
}

/** [member]'s dialog has something on settling together for [me] (see [SettleTogetherSection]). */
fun hasSettleTogether(band: Band, me: Member, member: Member): Boolean = when {
    member.settlesWith != null -> true
    member.id == me.id -> me.invitedToSettle != null || SettleTogether.inviterOf(band, me.id) != null
    me.invitedToSettle == member.id || member.invitedToSettle == me.id -> true
    member.invitedToSettle != null || SettleTogether.inviterOf(band, member.id) != null -> true
    else -> SettleTogether.mayInvite(band, me, member)
}

/** On top of the events list while someone's invitation to settle together waits for [me] (ITERATION_5 §7). */
@Composable
fun SettleTogetherInvitationCard(band: Band, me: Member?, actions: Actions) {
    val from = me?.let { SettleTogether.inviterOf(band, it.id) } ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) { InvitationAnswer(from, me, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvitationAnswer(from: Member, me: Member, actions: Actions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.settle_together_invited, from.name))
        Text(stringResource(Res.string.settle_together_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.run(AcceptSettleTogether(from.id, me.id)) }) { Text(stringResource(Res.string.settle_together_accept)) }
            OutlinedButton(onClick = { actions.run(DeclineSettleTogether(from.id, me.id)) }) { Text(stringResource(Res.string.settle_together_decline)) }
        }
    }
}

/** A new [role] for [member] ends their pair: a viewer, or a second artist (ITERATION_5 §7). */
fun pairEndsWarningNeeded(band: Band, member: Member, role: Role): Boolean {
    val partner = member.settlesWith?.let { band.member(it) } ?: return false
    return role == Role.VIEWER || (role == Role.ARTIST && partner.role == Role.ARTIST)
}

/**
 * The line a confirmation adds when archiving, unlinking or a new [role] (null: not a role change) ends [member]'s pair
 * (ITERATION_5 §7); null when it doesn't.
 */
@Composable
fun pairEndsWarning(band: Band, member: Member, role: Role? = null): String? {
    val partner = member.settlesWith?.let { band.member(it) } ?: return null
    if (role != null && !pairEndsWarningNeeded(band, member, role)) return null
    return stringResource(Res.string.settle_together_ends, member.name, partner.name, SettleTogether.openEventsTogether(band, member.id, partner.id).toString())
}
