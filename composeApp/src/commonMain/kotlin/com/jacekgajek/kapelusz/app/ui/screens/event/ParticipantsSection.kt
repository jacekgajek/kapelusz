package com.jacekgajek.kapelusz.app.ui.screens.event

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

    SectionTitle(stringResource(Res.string.participants))
    // One row each (ITERATION_5 §4.5): members in roster order, then guests; the share when not the default 100%.
    val playing = band.members.filter { event.participant(it.id) != null }.map { it.id } + event.guests.map { it.id }
    playing.forEach { partyId ->
        val participant = event.participant(partyId) ?: return@forEach
        key(partyId) { ParticipantRow(context, participant, canManage, onEdit = { editingWeight = partyId }) }
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
private fun ParticipantRow(context: EventContext, participant: Participant, canManage: Boolean, onEdit: () -> Unit) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, band.member(partyId)?.pictureUrl, decorative = true)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name)
            share?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (canManage) {
            EditButton(onEdit)
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
            ) { Text(stringResource(Res.string.ok)) }
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
