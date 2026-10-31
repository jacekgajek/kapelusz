package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_guest
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.guest_add_anyway
import com.jacekgajek.kapelusz.app.generated.guest_add_as_guest
import com.jacekgajek.kapelusz.app.generated.guest_add_member_instead
import com.jacekgajek.kapelusz.app.generated.guest_is_member
import com.jacekgajek.kapelusz.app.generated.guest_name
import com.jacekgajek.kapelusz.app.generated.guest_removed
import com.jacekgajek.kapelusz.app.generated.lineup_add
import com.jacekgajek.kapelusz.app.generated.lineup_changed
import com.jacekgajek.kapelusz.app.generated.lineup_not_taking_part
import com.jacekgajek.kapelusz.app.generated.lineup_start_from
import com.jacekgajek.kapelusz.app.generated.not_in_app_invite
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.participant_removed
import com.jacekgajek.kapelusz.app.generated.participants
import com.jacekgajek.kapelusz.app.generated.participants_count
import com.jacekgajek.kapelusz.app.generated.participants_guests
import com.jacekgajek.kapelusz.app.generated.share_resulting
import com.jacekgajek.kapelusz.app.generated.share_resulting_one
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.Crowd
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.FormerMember
import com.jacekgajek.kapelusz.app.ui.components.InviteMemberDialog
import com.jacekgajek.kapelusz.app.ui.components.PartyAvatar
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.ShareEditor
import com.jacekgajek.kapelusz.app.ui.components.ShareMode
import com.jacekgajek.kapelusz.app.ui.components.formerMemberNamed
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberShareEditorState
import com.jacekgajek.kapelusz.app.ui.components.resolved
import com.jacekgajek.kapelusz.app.ui.components.shareText
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.engine.Limits
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.ChangeLineup
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.SetFixedShare
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.rules.Lineup
import com.jacekgajek.kapelusz.settlement.SettlementCalculator
import com.jacekgajek.kapelusz.view.partyName
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParticipantsSection(context: EventContext) {
    val band = context.band
    val event = context.event
    val canManage = context.can.manageParticipants(event)
    var dialog by remember { mutableStateOf<ParticipantsDialog?>(null) }
    // Who may invite members without the app (I5 §12).
    val canInvite = context.can.manageRoster()
    var copying by remember { mutableStateOf<ChangeLineup?>(null) }
    // Members, then guests, each alphabetical (I8 §2).
    val order = SettlementCalculator.partyOrder(band, event)
    val crowded = order.size > Crowd.THRESHOLD
    SectionTitle(if (crowded) stringResource(Res.string.participants_count, order.size) else stringResource(Res.string.participants))
    // One row each (I5 §4.5), guests under their own heading (§12); the share when not the default 100%.
    // All of them, however many (I8 §1.4).
    val (memberRows, guestRows) = order.partition { band.member(it) != null }
    @Composable
    fun rows(ids: List<String>) = ids.forEach { partyId ->
        val participant = event.participant(partyId) ?: return@forEach
        key(partyId) {
            val invite = { dialog = ParticipantsDialog.Inviting(partyId) }
            val editShare = { dialog = ParticipantsDialog.EditingShare(partyId) }
            ParticipantRow(context, participant, canManage, onEdit = editShare, onInvite = invite.takeIf { canInvite })
        }
    }
    rows(memberRows)
    if (guestRows.isNotEmpty()) {
        Text(
            stringResource(Res.string.participants_guests),
            Modifier.padding(top = 8.dp, bottom = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows(guestRows)
    }
    // Who could take part but does not: a tap adds them, with their default share; too many for chips, the picker
    // (I8 §1.1). Viewers only watch (they take no part in events).
    val notTakingPart = Lineup.candidates(band).filter { event.participant(it.id) == null }
    val chips = notTakingPart.size <= Crowd.THRESHOLD
    if (chips && notTakingPart.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            notTakingPart.forEach { member ->
                FilterChip(
                    selected = false,
                    enabled = canManage,
                    onClick = { context.actions.run(AddParticipant(event.id, member.id, member.shareWeight, member.shareFixed, member.sharePercent)) },
                    label = { Text(member.name) },
                    // The member's Google photo, or their initials (I2 §7.4).
                    leadingIcon = { Avatar(member.name, member.pictureUrl, 18.dp, decorative = true) },
                )
            }
        }
    }
    // The actions on a row of their own, below the people, all alike (I8 §1.2a): another event's line-up
    // while there is nobody yet; the picker past the threshold, or while there is nobody yet, for any band size.
    val empty = order.isEmpty()
    val picker = notTakingPart.isNotEmpty() && (!chips || empty)
    if (canManage) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            if (empty) {
                AssistChip(
                    onClick = { dialog = ParticipantsDialog.ChoosingSource },
                    label = { Text(stringResource(Res.string.lineup_start_from)) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                )
            }
            if (picker) {
                AssistChip(
                    onClick = { dialog = ParticipantsDialog.Picking },
                    label = { Text(stringResource(Res.string.lineup_add, notTakingPart.size)) },
                    leadingIcon = { Icon(Icons.Default.GroupAdd, null) },
                )
            }
            AssistChip(
                onClick = { dialog = ParticipantsDialog.AddingGuest },
                label = { Text(stringResource(Res.string.add_guest)) },
                leadingIcon = { Icon(Icons.Default.Add, null) },
            )
        }
    } else if (!chips) {
        // Information rather than an action: who isn't taking part, read only.
        TextButton(onClick = { dialog = ParticipantsDialog.Picking }) {
            Text(stringResource(Res.string.lineup_not_taking_part, notTakingPart.size))
        }
    }

    when (val open = dialog) {
        ParticipantsDialog.ChoosingSource -> StartFromDialog(context, onDismiss = { dialog = null }) { source ->
            dialog = null
            copying = Lineup.change(band, event, Lineup.startFrom(band, event, source) { newId() })
        }

        ParticipantsDialog.Picking -> LineupPicker(context, readOnly = !canManage, onDismiss = { dialog = null })

        ParticipantsDialog.AddingGuest -> GuestDialog(context, onDismiss = { dialog = null }) { name, share ->
            context.actions.run(AddGuest(event.id, newId(), name, share.weight, share.fixedShare, share.percent))
        }

        is ParticipantsDialog.Inviting -> InviteThemDialog(context, open.partyId, onDismiss = { dialog = null })

        is ParticipantsDialog.EditingShare -> {
            val participant = resolved(event.participant(open.partyId), onDismiss = { dialog = null })
            if (participant != null) ShareDialog(context, participant, onDismiss = { dialog = null })
        }

        null -> Unit
    }
    // Applied at once, with Undo (its message needs the counts, hence here rather than in the click).
    copying?.let { op ->
        val message = stringResource(Res.string.lineup_changed, op.add.size, op.remove.size)
        LaunchedEffect(op) {
            context.actions.runWithUndo(op, message)
            copying = null
        }
    }
}

/** What is open over the participants: one at a time. */
private sealed interface ParticipantsDialog {
    data object AddingGuest : ParticipantsDialog
    data object Picking : ParticipantsDialog
    data object ChoosingSource : ParticipantsDialog
    data class EditingShare(val partyId: String) : ParticipantsDialog
    data class Inviting(val partyId: String) : ParticipantsDialog
}

/** A participant: avatar, name, their share when not the default; edit and remove (with Undo) for who manages them. */
@Composable
private fun ParticipantRow(context: EventContext, participant: Participant, canManage: Boolean, onEdit: () -> Unit, onInvite: (() -> Unit)?) {
    val band = context.band
    val event = context.event
    val partyId = participant.partyId
    val name = partyName(band, event, partyId, "")
    val guest = event.guest(partyId) != null
    val share = shareText(participant.weight, participant.fixedShare, participant.percent, context.currency)
    val removed = stringResource(if (guest) Res.string.guest_removed else Res.string.participant_removed, name)
    // A member without the app can't see the band: the crossed eye, and (leaders) a tap to invite them (§12). A guest
    // can't either, but is only ever a guest: the handshake, and no tap.
    val notInApp = !guest && band.member(partyId)?.userId == null
    val invite = onInvite?.takeIf { notInApp }
    val inviteLabel = stringResource(Res.string.not_in_app_invite)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).let { if (invite != null) it.clickable(onClickLabel = inviteLabel, onClick = invite) else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartyAvatar(band, event, partyId)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name)
                share?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (canManage) {
            EditButton(name, onEdit)
            // At once, with Undo: it brings them back with their share (I5 §4.5).
            RemoveButton(name) { context.actions.runWithUndo(RemoveParticipant(event.id, partyId), removed) }
        }
    }
}

/** A new guest's name and share: shares, a percentage or a fixed amount, as in [ShareDialog]. */
@Composable
private fun GuestDialog(context: EventContext, onDismiss: () -> Unit, onAdd: (String, Participant) -> Boolean) {
    var name by remember { mutableStateOf("") }
    val editor = rememberShareEditorState(DEFAULT_WEIGHT, null, null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_guest)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(Limits.MAX_NAME) }, label = { Text(stringResource(Res.string.guest_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // Probably the member, typed as a guest (I5 §12): offered as a participant; a guest still possible.
                val former = context.band.formerMemberNamed(name)
                // Or someone who used to be in the band: welcomed back, and into this event.
                if (former != null) {
                    FormerMember(former, context.actions) {
                        // Viewers only watch: restored, but not in the event.
                        if (former.role != Role.VIEWER && context.event.participant(former.id) == null) context.actions.run(AddParticipant(context.event.id, former.id, former.shareWeight, former.shareFixed, former.sharePercent))
                        onDismiss()
                    }
                }
                context.band.memberNamed(name)?.takeIf { name.isNotBlank() && !it.archived }?.let { member ->
                    Text(stringResource(Res.string.guest_is_member, member.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (context.event.participant(member.id) == null) {
                        TextButton(onClick = {
                            if (context.actions.run(AddParticipant(context.event.id, member.id, member.shareWeight, member.shareFixed, member.sharePercent))) onDismiss()
                        }) { Text(stringResource(Res.string.guest_add_member_instead, member.name)) }
                    }
                }
                ShareEditor(editor, context.currency, percentTaken(context.event, NEW_GUEST)) {
                    val guest = editor.applyTo(Participant(NEW_GUEST))
                    val preview = context.event.copy(guests = context.event.guests + Guest(NEW_GUEST, name), participants = context.event.participants + guest)
                    SharePreview(context, preview, NEW_GUEST, editor.mode)
                }
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && editor.valid,
                onClick = { if (onAdd(name.trim(), editor.applyTo(Participant(NEW_GUEST)))) onDismiss() },
            ) {
                val member = context.band.memberNamed(name)?.takeIf { name.isNotBlank() }
                Text(
                    stringResource(
                        when {
                            member == null -> Res.string.ok
                            member.archived -> Res.string.guest_add_as_guest
                            else -> Res.string.guest_add_anyway
                        },
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The guest being added, in the preview of what their share comes to. */
private const val NEW_GUEST = "new-guest"

/** What the percentages of everyone but [partyId], the fund's included, already take of the profit (I7 §6). */
private fun percentTaken(event: Event, partyId: String): Int = event.participants.filter { it.partyId != partyId }.sumOf { it.percent ?: 0 } + (event.fundPercent ?: 0)

/** What [partyId]'s share comes to in [preview]; by shares, also what one share does. */
@Composable
private fun SharePreview(context: EventContext, preview: Event, partyId: String, mode: ShareMode) {
    val lang = language
    val settlement = SettlementCalculator.settle(context.band, preview)
    val share = formatMoney(settlement.line(partyId)?.share ?: Money.ZERO, context.currency, lang)
    val weights = preview.participants.filter { it.byWeight }.sumOf { it.weight.toLong() }
    val one = Money(settlement.rest.minor * DEFAULT_WEIGHT / weights.coerceAtLeast(1))
        .takeIf { mode == ShareMode.WEIGHT && settlement.rest.isPositive && weights > 0 }
    Text(
        one?.let { stringResource(Res.string.share_resulting_one, share, formatMoney(it, context.currency, lang)) }
            ?: stringResource(Res.string.share_resulting, share),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * A participant's share (I3 §2.4, I7 §6): shares on a slider in quarters, a percentage of the profit,
 * or a fixed amount. OK applies the change in one operation, so one undo reverts the whole dialog; the weight stays
 * underneath a fixed amount or a percentage, so switching back restores it.
 */
@Composable
private fun ShareDialog(context: EventContext, participant: Participant, onDismiss: () -> Unit) {
    val event = context.event
    val editor = rememberShareEditorState(participant.weight, participant.fixedShare, participant.percent)

    val target = editor.applyTo(participant)
    val ops = when {
        !editor.valid || target == participant -> emptyList()

        participant.byWeight && target.byWeight -> listOf(SetWeight(event.id, participant.partyId, target.weight))

        else -> listOf(
            SetFixedShare(event.id, participant.partyId, target.fixedShare, target.weight.takeIf { it != participant.weight }, target.percent),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(partyName(context.band, event, participant.partyId, "")) },
        text = {
            ShareEditor(editor, context.currency, percentTaken(event, participant.partyId)) {
                val preview = event.copy(participants = event.participants.map { if (it.partyId == participant.partyId) editor.applyTo(it) else it })
                SharePreview(context, preview, participant.partyId, editor.mode)
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            TextButton(
                enabled = editor.valid,
                onClick = {
                    // Refused (e.g. removed from the event meanwhile): the dialog stays (I5 C4c).
                    if (ops.all { context.actions.run(it) }) onDismiss()
                },
            ) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A member without the app (I5 §12), tapped by a leader: "Ola cannot see the band. Invite her?". */
@Composable
private fun InviteThemDialog(context: EventContext, memberId: String, onDismiss: () -> Unit) {
    val member = resolved(context.band.member(memberId), onDismiss) ?: return
    InviteMemberDialog(member.name, onInvite = { context.onInviteMember(memberId) }, onDismiss = onDismiss)
}
