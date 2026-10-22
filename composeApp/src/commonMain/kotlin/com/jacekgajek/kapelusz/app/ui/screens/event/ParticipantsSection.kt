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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import com.jacekgajek.kapelusz.app.ui.components.removeLabel
import androidx.compose.ui.semantics.Role
import com.jacekgajek.kapelusz.app.generated.share_weight
import com.jacekgajek.kapelusz.app.generated.share_weight_hint
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.model.DEFAULT_WEIGHT
import com.jacekgajek.kapelusz.ops.AddGuest
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.RemoveParticipant
import com.jacekgajek.kapelusz.ops.SetWeight
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jacekgajek.kapelusz.app.generated.share_by_percent
import com.jacekgajek.kapelusz.app.generated.share_fixed
import com.jacekgajek.kapelusz.app.generated.share_fixed_amount
import com.jacekgajek.kapelusz.app.generated.share_fixed_hint
import com.jacekgajek.kapelusz.app.generated.share_resulting
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.Limits
import com.jacekgajek.kapelusz.ops.SetFixedShare
import kotlin.math.roundToInt
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
                        // A button named after the guest, not a bare "Remove" image (ITERATION_3 R13).
                        Icon(
                            Icons.Default.Close,
                            removeLabel(guest.name),
                            Modifier.clickable(role = Role.Button) { context.actions.run(RemoveParticipant(event.id, guest.id)) },
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
        GuestDialog(onDismiss = { addingGuest = false }) { name, weight ->
            context.actions.run(AddGuest(event.id, newId(), name, weight))
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

/**
 * A participant's share (ITERATION_3 §2.4): a percentage on a slider in 25% steps, or a fixed amount. OK applies the
 * change; the weight stays as it was while the share is fixed, so switching back restores it.
 */
@Composable
private fun ShareDialog(context: EventContext, participant: Participant, onDismiss: () -> Unit) {
    val event = context.event
    val lang = language
    var fixed by remember { mutableStateOf(participant.fixedShare != null) }
    var weight by remember { mutableStateOf(participant.weight) }
    var amount by remember { mutableStateOf(participant.fixedShare?.let { formatMoneyInput(it, lang) } ?: "") }
    val parsedAmount = parseMoney(amount)?.takeIf { it.isPositive && it.minor <= Limits.MAX_AMOUNT }
    val currencyLabel = if (lang == "pl" && context.currency == "PLN") "zł" else context.currency

    val ops = buildList {
        if (fixed) {
            if (parsedAmount != null && parsedAmount != participant.fixedShare) add(SetFixedShare(event.id, participant.partyId, parsedAmount))
        } else {
            val newWeight = weight.takeIf { it != participant.weight }
            // Back to a percentage with a new weight in one operation, so one undo reverts the whole dialog.
            if (participant.fixedShare != null) add(SetFixedShare(event.id, participant.partyId, null, newWeight))
            else if (newWeight != null) add(SetWeight(event.id, participant.partyId, newWeight))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(partyName(context.band, event, participant.partyId, "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "%", true to currencyLabel).forEachIndexed { index, (value, label) ->
                        val description = stringResource(if (value) Res.string.share_fixed_amount else Res.string.share_by_percent)
                        SegmentedButton(
                            selected = fixed == value,
                            onClick = { fixed = value },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.semantics { contentDescription = description },
                        ) { Text(label) }
                    }
                }
                if (fixed) {
                    MoneyField(amount, { amount = it }, stringResource(Res.string.share_fixed_amount))
                    Text(stringResource(Res.string.share_fixed_hint), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("$weight%", style = MaterialTheme.typography.titleMedium)
                    // A weight off the steps (e.g. 110% from before) shows as is; the slider snaps it only when moved.
                    Slider(
                        value = weight.coerceIn(0, MAX_SLIDER_WEIGHT).toFloat(),
                        onValueChange = { weight = (it / WEIGHT_STEP).roundToInt() * WEIGHT_STEP },
                        valueRange = 0f..MAX_SLIDER_WEIGHT.toFloat(),
                        steps = MAX_SLIDER_WEIGHT / WEIGHT_STEP - 1,
                    )
                    val preview = event.copy(participants = event.participants.map { if (it.partyId == participant.partyId) it.copy(weight = weight, fixedShare = null) else it })
                    val share = SettlementCalculator.settle(context.band, preview).line(participant.partyId)?.share ?: Money.ZERO
                    Text(stringResource(Res.string.share_resulting, formatMoney(share, context.currency, lang)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(Res.string.share_weight_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !fixed || parsedAmount != null,
                onClick = {
                    ops.all { context.actions.run(it) }
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

private const val WEIGHT_STEP = 25
private const val MAX_SLIDER_WEIGHT = 200

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
