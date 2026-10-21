package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jacekgajek.artsplit.ops.SetEventAgenda
import com.jacekgajek.artsplit.model.effectiveAgendaTemplate
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.app.ui.model.formatAgendaTime
import com.jacekgajek.artsplit.app.ui.model.displayLabel
import com.jacekgajek.artsplit.app.ui.components.TimePickerDialog
import com.jacekgajek.artsplit.app.ui.components.RemoveButton
import com.jacekgajek.artsplit.app.ui.components.TextInputDialog
import com.jacekgajek.artsplit.app.generated.agenda_label
import com.jacekgajek.artsplit.app.generated.agenda_add_time
import com.jacekgajek.artsplit.app.generated.more_details
import com.jacekgajek.artsplit.app.data.newId
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.archive
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.delete
import com.jacekgajek.artsplit.app.generated.delete_event_confirm
import com.jacekgajek.artsplit.app.generated.delete_event_title
import com.jacekgajek.artsplit.app.generated.edit_details
import com.jacekgajek.artsplit.app.generated.event_date
import com.jacekgajek.artsplit.app.generated.event_location
import com.jacekgajek.artsplit.app.generated.event_name
import com.jacekgajek.artsplit.app.generated.more
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.generated.unarchive
import com.jacekgajek.artsplit.app.ui.components.Actions
import com.jacekgajek.artsplit.app.ui.components.ConfirmDialog
import com.jacekgajek.artsplit.app.ui.components.DateField
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Event
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.DeleteEvent
import com.jacekgajek.artsplit.ops.SetEventArchived
import com.jacekgajek.artsplit.ops.UpdateEvent
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditDetailsDialog(band: Band, event: Event, actions: Actions, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(event.name) }
    var date by remember { mutableStateOf(event.date) }
    var location by remember { mutableStateOf(event.location.orEmpty()) }
    // Events from before agenda times get the band's template, all unset (VERSION_2 §4.1).
    var agenda by remember {
        mutableStateOf(event.agenda.ifEmpty { band.effectiveAgendaTemplate.map { AgendaTime(newId(), it.kind, it.label) } })
    }
    // Unset times stay behind "More details" until picked, like address fields in a phone's contacts.
    var moreDetails by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<AgendaTime?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.event_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(Res.string.event_date))
                OutlinedTextField(location, { location = it }, label = { Text(stringResource(Res.string.event_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                agenda.filter { it.time != null }.sortedBy { it.minutesOfEventDay }.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.displayLabel(), Modifier.weight(1f))
                        TextButton(onClick = { picking = item }) { Text(formatAgendaTime(item)) }
                        // Built-in and template times go back behind "More details"; one-off times disappear.
                        RemoveButton { agenda = if (item.kind != null || isFromTemplate(band, item)) agenda.map { if (it.id == item.id) it.copy(time = null) else it } else agenda - item }
                    }
                }
                val unset = agenda.filter { it.time == null }
                if (!moreDetails) {
                    TextButton(onClick = { moreDetails = true }) { Text(stringResource(Res.string.more_details)) }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        unset.forEach { item ->
                            AssistChip(onClick = { picking = item }, label = { Text("+ " + item.displayLabel()) })
                        }
                        AssistChip(onClick = { addingCustom = true }, label = { Text(stringResource(Res.string.agenda_add_time)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val update = UpdateEvent(
                    event.id,
                    name = Change(name).takeIf { name.trim() != event.name },
                    date = Change(date).takeIf { date != event.date },
                    location = Change(location.ifBlank { null }).takeIf { location.trim() != event.location.orEmpty() },
                )
                if (listOf(update.name, update.date, update.location).any { it != null }) actions.run(update)
                if (agenda != event.agenda && !(event.agenda.isEmpty() && agenda.all { it.time == null })) actions.run(SetEventAgenda(event.id, agenda))
                onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
    picking?.let { item ->
        TimePickerDialog(item.time, item.displayLabel(), onTime = { time ->
            agenda = if (agenda.any { it.id == item.id }) agenda.map { if (it.id == item.id) it.copy(time = time) else it } else agenda + item.copy(time = time)
        }, onDismiss = { picking = null })
    }
    if (addingCustom) {
        TextInputDialog(
            title = stringResource(Res.string.agenda_add_time),
            label = stringResource(Res.string.agenda_label),
            initial = "",
            onConfirm = { label -> picking = AgendaTime(newId(), label = label.take(AgendaTime.MAX_LABEL)) },
            onDismiss = { addingCustom = false },
        )
    }
}

/** A custom time that came with the band's template (kept, unset, when removed) rather than a one-off. */
private fun isFromTemplate(band: Band, item: AgendaTime) = band.effectiveAgendaTemplate.any { it.kind == null && it.label == item.label }

/** Overflow menu: archive / restore, delete (only events without recorded transfers can be deleted). */
@Composable
fun EventMenu(band: Band, event: Event, actions: Actions, onDeleted: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(Res.string.more)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(if (event.archived) Res.string.unarchive else Res.string.archive)) },
            onClick = { open = false; actions.run(SetEventArchived(event.id, !event.archived)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.delete)) },
            enabled = event.transfers.isEmpty(),
            onClick = { open = false; confirmDelete = true },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(Res.string.delete_event_title),
            text = stringResource(Res.string.delete_event_confirm, event.name),
            confirmLabel = stringResource(Res.string.delete),
            onConfirm = { if (actions.run(DeleteEvent(event.id))) onDeleted() },
            onDismiss = { confirmDelete = false },
        )
    }
}
