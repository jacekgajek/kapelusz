package com.jacekgajek.kapelusz.app.ui.screens.event

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.LaunchedEffect
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.generated.not_in_app_failed
import com.jacekgajek.kapelusz.app.generated.not_in_app_offline
import com.jacekgajek.kapelusz.app.generated.participants_guests
import com.jacekgajek.kapelusz.app.generated.guest_is_member
import com.jacekgajek.kapelusz.app.generated.guest_add_member_instead
import com.jacekgajek.kapelusz.app.generated.guest_add_anyway
import com.jacekgajek.kapelusz.app.generated.not_in_app_text
import com.jacekgajek.kapelusz.app.generated.member_name
import com.jacekgajek.kapelusz.app.generated.not_in_app_saving
import com.jacekgajek.kapelusz.app.generated.not_in_app_invite
import com.jacekgajek.kapelusz.app.ui.components.NotInAppIcon
import com.jacekgajek.kapelusz.app.ui.components.nameTakenError
import com.jacekgajek.kapelusz.ops.PromoteGuest
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.jacekgajek.kapelusz.app.data.awaitPushed
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_guest
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.guest_name
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.participants
import com.jacekgajek.kapelusz.app.ui.components.rememberShareEditorState
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.ShareEditor
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.ops.AddGuest
import androidx.compose.runtime.key
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.generated.participant_removed
import com.jacekgajek.kapelusz.app.generated.guest_removed
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.app.generated.share_fixed
import com.jacekgajek.kapelusz.app.generated.share_resulting
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.SetFixedShare
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParticipantsSection(context: EventContext) {
    val band = context.band
    val event = context.event
    val canManage = context.can.manageParticipants(event)
    var addingGuest by remember { mutableStateOf(false) }
    var editingWeight by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf<String?>(null) }
    // Outlives the invite dialog: promoting a guest closes it (the guest is gone), and the wait for the save goes on.
    val scope = rememberCoroutineScope()
    // Who may make a guest a member and invite people (ITERATION_5 §12).
    val canInvite = context.can.manageRoster()

    SectionTitle(stringResource(Res.string.participants))
    // One row each (ITERATION_5 §4.5): members in roster order, then guests under their own heading (§12); the share
    // when not the default 100%.
    @Composable
    fun rows(ids: List<String>) = ids.forEach { partyId ->
        val participant = event.participant(partyId) ?: return@forEach
        key(partyId) {
            ParticipantRow(context, participant, canManage, onEdit = { editingWeight = partyId }, onInvite = { inviting = partyId }.takeIf { canInvite })
        }
    }
    rows(band.members.filter { event.participant(it.id) != null }.map { it.id })
    val guests = event.guests.map { it.id }.filter { event.participant(it) != null }
    if (guests.isNotEmpty()) {
        Text(
            stringResource(Res.string.participants_guests),
            Modifier.padding(top = 8.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows(guests)
    }
    // Who could take part but does not: a tap adds them, with their default share.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Viewers only watch (they take no part in events).
        band.members.filter { !it.archived && it.role != Role.VIEWER && event.participant(it.id) == null }.forEach { member ->
            FilterChip(
                selected = false,
                enabled = canManage,
                onClick = { context.actions.run(AddParticipant(event.id, member.id, member.shareWeight, member.shareFixed)) },
                label = { Text(member.name) },
                // The member's Google photo, or their initials (ITERATION_2 §7.4).
                leadingIcon = { Avatar(member.name, member.pictureUrl, 18.dp, decorative = true) },
            )
        }
        if (canManage) AssistChip(
            onClick = { addingGuest = true },
            label = { Text(stringResource(Res.string.add_guest)) },
            leadingIcon = { Icon(Icons.Default.Add, null) },
        )
    }

    if (addingGuest) {
        GuestDialog(context, onDismiss = { addingGuest = false }) { name, weight, fixedShare ->
            context.actions.run(AddGuest(event.id, newId(), name, weight, fixedShare))
        }
    }
    inviting?.let { partyId -> InviteThemDialog(context, partyId, scope, onDismiss = { inviting = null }) }
    editingWeight?.let { partyId ->
        val participant = event.participant(partyId)
        if (participant == null) {
            editingWeight = null
        } else {
            ShareDialog(context, participant, onDismiss = { editingWeight = null })
        }
    }
}

/** A participant: avatar, name, their share when not the default; edit and remove (with Undo) for who manages them. */
@Composable
private fun ParticipantRow(context: EventContext, participant: Participant, canManage: Boolean, onEdit: () -> Unit, onInvite: (() -> Unit)?) {
    val band = context.band
    val event = context.event
    val partyId = participant.partyId
    val name = partyName(band, event, partyId, "")
    val guest = event.guest(partyId) != null
    val fixed = participant.fixedShare
    val share = when {
        fixed != null -> "${formatMoney(fixed, context.currency, language)} · ${stringResource(Res.string.share_fixed)}"
        participant.weight != DEFAULT_WEIGHT -> "${participant.weight}%"
        else -> null
    }
    val removed = stringResource(if (guest) Res.string.guest_removed else Res.string.participant_removed, name)
    // A guest or a member without the app can't see the band: the crossed eye, and (leaders) a tap to invite them (§12).
    val notInApp = guest || band.member(partyId)?.userId == null
    val invite = onInvite?.takeIf { notInApp }
    val inviteLabel = stringResource(Res.string.not_in_app_invite)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).let { if (invite != null) it.clickable(onClickLabel = inviteLabel, onClick = invite) else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (notInApp) NotInAppIcon() else Avatar(name, band.member(partyId)?.pictureUrl, decorative = true)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name)
            share?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        }
        if (canManage) {
            EditButton(name, onEdit)
            // At once, with Undo: it brings them back with their share (ITERATION_5 §4.5).
            RemoveButton(name) { context.actions.runWithUndo(RemoveParticipant(event.id, partyId), removed) }
        }
    }
}

/** A new guest's name and share: a percentage on the slider, or a fixed amount, as in [ShareDialog]. */
@Composable
private fun GuestDialog(context: EventContext, onDismiss: () -> Unit, onAdd: (String, Int, Money?) -> Boolean) {
    val lang = language
    var name by remember { mutableStateOf("") }
    val editor = rememberShareEditorState(DEFAULT_WEIGHT, null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_guest)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(Limits.MAX_NAME) }, label = { Text(stringResource(Res.string.guest_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // Probably the member, typed as a guest (ITERATION_5 §12): offered as a participant; a guest still possible.
                context.band.memberNamed(name)?.takeIf { name.isNotBlank() && !it.archived }?.let { member ->
                    Text(stringResource(Res.string.guest_is_member, member.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (context.event.participant(member.id) == null) TextButton(onClick = {
                        if (context.actions.run(AddParticipant(context.event.id, member.id, member.shareWeight, member.shareFixed))) onDismiss()
                    }) { Text(stringResource(Res.string.guest_add_member_instead, member.name)) }
                }
                ShareEditor(editor, context.currency) {
                    val guest = Participant(NEW_GUEST, editor.weight)
                    val preview = context.event.copy(guests = context.event.guests + Guest(NEW_GUEST, name), participants = context.event.participants + guest)
                    val share = SettlementCalculator.settle(context.band, preview).line(NEW_GUEST)?.share ?: Money.ZERO
                    Text(stringResource(Res.string.share_resulting, formatMoney(share, context.currency, lang)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && editor.valid,
                onClick = { if (onAdd(name.trim(), editor.weight, editor.parsedAmount.takeIf { editor.fixed })) onDismiss() },
            ) { Text(stringResource(if (context.band.memberNamed(name)?.takeIf { name.isNotBlank() && !it.archived } != null) Res.string.guest_add_anyway else Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The guest being added, in the preview of what their share comes to. */
private const val NEW_GUEST = "new-guest"

/**
 * A participant's share (ITERATION_3 §2.4): a percentage on a slider in 25% steps, or a fixed amount. OK applies the
 * change; the weight stays as it was while the share is fixed, so switching back restores it.
 */
@Composable
private fun ShareDialog(context: EventContext, participant: Participant, onDismiss: () -> Unit) {
    val event = context.event
    val lang = language
    val editor = rememberShareEditorState(participant.weight, participant.fixedShare)

    val ops = buildList {
        if (editor.fixed) {
            val amount = editor.parsedAmount
            if (amount != null && amount != participant.fixedShare) add(SetFixedShare(event.id, participant.partyId, amount))
        } else {
            val newWeight = editor.weight.takeIf { it != participant.weight }
            // Back to a percentage with a new weight in one operation, so one undo reverts the whole dialog.
            if (participant.fixedShare != null) add(SetFixedShare(event.id, participant.partyId, null, newWeight))
            else if (newWeight != null) add(SetWeight(event.id, participant.partyId, newWeight))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(partyName(context.band, event, participant.partyId, "")) },
        text = {
            ShareEditor(editor, context.currency) {
                val preview = event.copy(participants = event.participants.map { if (it.partyId == participant.partyId) it.copy(weight = editor.weight, fixedShare = null) else it })
                val share = SettlementCalculator.settle(context.band, preview).line(participant.partyId)?.share ?: Money.ZERO
                Text(stringResource(Res.string.share_resulting, formatMoney(share, context.currency, lang)), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                enabled = editor.valid,
                onClick = {
                    // Refused (e.g. removed from the event meanwhile): the dialog stays (V5-M1 C4c).
                    if (ops.all { context.actions.run(it) }) onDismiss()
                },
            ) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Someone who can't see the band (ITERATION_5 §12), tapped by a leader: "Ola cannot see the band. Invite her?". A guest
 * becomes a band member first (in this event only, under a free name), saved to the server, then Band members opens at
 * them to create the invite; a member without the app goes there straight away.
 */
@Composable
private fun InviteThemDialog(context: EventContext, partyId: String, scope: CoroutineScope, onDismiss: () -> Unit) {
    val event = context.event
    // As when tapped: once promoted, the guest is no longer in the event.
    val guest = remember(partyId) { event.guest(partyId) }
    val member = remember(partyId) { context.band.member(partyId) }
    val shownName = guest?.name ?: member?.name
    if (shownName == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var name by remember { mutableStateOf(shownName) }
    val saving = stringResource(Res.string.not_in_app_saving)
    val failed = stringResource(Res.string.not_in_app_failed)
    val offline = stringResource(Res.string.not_in_app_offline)
    val taken = if (guest != null) nameTakenError(context.band, name) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.not_in_app_text, shownName))
                if (guest != null) {
                    // The guest's name is a member's already: a free one is needed for the new member.
                    if (taken != null || name != shownName) OutlinedTextField(
                        name,
                        { name = it.take(Limits.MAX_NAME) },
                        label = { Text(stringResource(Res.string.member_name)) },
                        singleLine = true,
                        isError = taken != null,
                        supportingText = taken?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && taken == null, onClick = {
                if (guest == null) {
                    onDismiss()
                    context.onInviteMember(partyId)
                    return@TextButton
                }
                val memberId = newId()
                if (!context.actions.run(PromoteGuest(event.id, guest.id, memberId, name.trim()))) return@TextButton
                onDismiss()
                context.actions.say(saving)
                scope.launch {
                    // The server must have the new member before an invite to it can be created.
                    val sent = context.graph.awaitPushed(context.band.id)
                    when {
                        // Refused by the server meanwhile (dropped from the queue): the guest is back.
                        (context.graph.bands.state.value as? BandState.Ready)?.band?.member(memberId) == null -> context.actions.say(failed)
                        else -> {
                            // Offline: a member on this phone; the invite waits for a connection.
                            if (!sent) context.actions.say(offline)
                            context.onInviteMember(memberId)
                        }
                    }
                }
            }) { Text(stringResource(Res.string.not_in_app_invite)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
