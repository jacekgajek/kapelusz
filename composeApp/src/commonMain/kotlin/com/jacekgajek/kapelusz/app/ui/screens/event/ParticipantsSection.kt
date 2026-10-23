package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.app.ui.components.Avatar
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
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
import com.jacekgajek.kapelusz.app.generated.adjust_shares
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.guest_name
import com.jacekgajek.kapelusz.app.generated.hide_shares
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.participants
import com.jacekgajek.kapelusz.app.ui.components.rememberShareEditorState
import com.jacekgajek.kapelusz.app.ui.components.removeLabel
import com.jacekgajek.kapelusz.app.generated.share_weight_hint
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.ShareEditor
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.ops.AddGuest
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
    var showShares by remember { mutableStateOf(event.participants.any { it.weight != DEFAULT_WEIGHT || it.fixedShare != null }) }
    var editingWeight by remember { mutableStateOf<String?>(null) }

    SectionTitle(stringResource(Res.string.participants))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Viewers only watch (they take no part in events).
        band.members.filter { (!it.archived && it.role != Role.VIEWER) || event.participant(it.id) != null }.forEach { member ->
            val selected = event.participant(member.id) != null
            FilterChip(
                selected = selected,
                enabled = canManage,
                onClick = {
                    context.actions.run(if (selected) RemoveParticipant(event.id, member.id) else AddParticipant(event.id, member.id, member.shareWeight, member.shareFixed))
                },
                label = { Text(member.name) },
                // The member's Google photo, or their initials (ITERATION_2 §7.4).
                leadingIcon = { Avatar(member.name, member.pictureUrl, 18.dp, decorative = true) },
            )
        }
        event.guests.forEach { guest ->
            InputChip(
                selected = true,
                onClick = {},
                label = { Text(guest.name) },
                trailingIcon = if (canManage) {
                    {
                        // A button named after the guest, not a bare "Remove" image (ITERATION_3 R13). Only the cross
                        // removes the guest: tapping the name did too, which was easy to do by accident.
                        Icon(
                            Icons.Default.Close,
                            removeLabel(guest.name),
                            Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { context.actions.run(RemoveParticipant(event.id, guest.id)) },
                        )
                    }
                } else {
                    null
                },
            )
        }
        if (canManage) AssistChip(
            onClick = { addingGuest = true },
            label = { Text(stringResource(Res.string.add_guest)) },
            leadingIcon = { Icon(Icons.Default.Add, null) },
        )
    }
    TextButton(onClick = { showShares = !showShares }) {
        Text(stringResource(if (showShares) Res.string.hide_shares else Res.string.adjust_shares))
    }
    if (showShares) {
        Column {
            event.participants.forEach { participant ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = canManage) { editingWeight = participant.partyId }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(partyName(band, event, participant.partyId, ""), Modifier.weight(1f))
                    val fixed = participant.fixedShare
                    Text(
                        if (fixed == null) {
                            "${participant.weight}%"
                        } else {
                            "${formatMoney(fixed, context.currency, language)} · ${stringResource(Res.string.share_fixed)}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Text(stringResource(Res.string.share_weight_hint), style = MaterialTheme.typography.bodySmall)
        }
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
