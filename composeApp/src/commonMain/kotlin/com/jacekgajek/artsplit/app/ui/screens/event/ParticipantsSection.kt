package com.jacekgajek.artsplit.app.ui.screens.event

import com.jacekgajek.artsplit.app.ui.components.Avatar
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.add_guest
import com.jacekgajek.artsplit.app.generated.adjust_shares
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.guest_name
import com.jacekgajek.artsplit.app.generated.hide_shares
import com.jacekgajek.artsplit.app.generated.ok
import com.jacekgajek.artsplit.app.generated.participants
import com.jacekgajek.artsplit.app.generated.remove
import com.jacekgajek.artsplit.app.generated.share_weight
import com.jacekgajek.artsplit.app.generated.share_weight_hint
import com.jacekgajek.artsplit.app.ui.components.SectionTitle
import com.jacekgajek.artsplit.app.ui.model.partyName
import com.jacekgajek.artsplit.model.DEFAULT_WEIGHT
import com.jacekgajek.artsplit.ops.AddGuest
import com.jacekgajek.artsplit.ops.AddParticipant
import com.jacekgajek.artsplit.ops.RemoveParticipant
import com.jacekgajek.artsplit.ops.SetWeight
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParticipantsSection(context: EventContext) {
    val band = context.band
    val event = context.event
    val canManage = context.can.manageParticipants(event)
    var addingGuest by remember { mutableStateOf(false) }
    var showShares by remember { mutableStateOf(event.participants.any { it.weight != DEFAULT_WEIGHT }) }
    var editingWeight by remember { mutableStateOf<String?>(null) }

    SectionTitle(stringResource(Res.string.participants))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        band.members.filter { !it.archived || event.participant(it.id) != null }.forEach { member ->
            val selected = event.participant(member.id) != null
            FilterChip(
                selected = selected,
                enabled = canManage,
                onClick = {
                    context.actions.run(if (selected) RemoveParticipant(event.id, member.id) else AddParticipant(event.id, member.id))
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
                        Icon(
                            Icons.Default.Close,
                            stringResource(Res.string.remove),
                            Modifier.clickable { context.actions.run(RemoveParticipant(event.id, guest.id)) },
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
                    Text("${participant.weight}%", style = MaterialTheme.typography.titleSmall)
                }
            }
            Text(stringResource(Res.string.share_weight_hint), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (addingGuest) {
        GuestDialog(onDismiss = { addingGuest = false }) { name, weight ->
            context.actions.run(AddGuest(event.id, newId(), name, weight))
        }
    }
    editingWeight?.let { partyId ->
        WeightDialog(
            title = partyName(band, event, partyId, ""),
            initial = event.participant(partyId)?.weight ?: DEFAULT_WEIGHT,
            onDismiss = { editingWeight = null },
        ) { context.actions.run(SetWeight(event.id, partyId, it)) }
    }
}

@Composable
private fun GuestDialog(onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf(DEFAULT_WEIGHT.toString()) }
    val parsedWeight = weight.toIntOrNull()?.takeIf { it in 0..10_000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_guest)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.guest_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                WeightField(weight) { weight = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && parsedWeight != null, onClick = { onAdd(name.trim(), parsedWeight!!); onDismiss() }) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun WeightDialog(title: String, initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var weight by remember { mutableStateOf(initial.toString()) }
    val parsed = weight.toIntOrNull()?.takeIf { it in 0..10_000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { WeightField(weight) { weight = it } },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { onSave(parsed!!); onDismiss() }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun WeightField(text: String, onText: (String) -> Unit) {
    Column {
        OutlinedTextField(
            text,
            { value -> onText(value.filter { it.isDigit() }.take(5)) },
            label = { Text(stringResource(Res.string.share_weight)) },
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(Res.string.share_weight_hint), style = MaterialTheme.typography.bodySmall)
    }
}
