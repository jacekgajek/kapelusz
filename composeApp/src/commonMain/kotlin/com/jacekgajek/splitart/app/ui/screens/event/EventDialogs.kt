package com.jacekgajek.splitart.app.ui.screens.event

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.jacekgajek.splitart.ops.SetEventAgenda
import com.jacekgajek.splitart.model.effectiveAgendaTemplate
import com.jacekgajek.splitart.model.AgendaTime
import com.jacekgajek.splitart.app.ui.model.formatAgendaTime
import com.jacekgajek.splitart.app.ui.model.displayLabel
import com.jacekgajek.splitart.app.ui.components.TimePickerDialog
import com.jacekgajek.splitart.app.ui.components.RemoveButton
import com.jacekgajek.splitart.app.ui.components.TextInputDialog
import com.jacekgajek.splitart.app.generated.agenda_label
import com.jacekgajek.splitart.app.generated.agenda_add_time
import com.jacekgajek.splitart.app.generated.more_details
import com.jacekgajek.splitart.app.data.newId
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.dp
import com.jacekgajek.splitart.app.generated.Res
import com.jacekgajek.splitart.app.generated.archive
import com.jacekgajek.splitart.app.generated.cancel
import com.jacekgajek.splitart.app.generated.delete
import com.jacekgajek.splitart.app.generated.delete_event_confirm
import com.jacekgajek.splitart.app.generated.delete_event_title
import com.jacekgajek.splitart.app.generated.edit_details
import com.jacekgajek.splitart.app.generated.event_date
import com.jacekgajek.splitart.app.generated.event_location
import com.jacekgajek.splitart.app.generated.event_name
import com.jacekgajek.splitart.app.generated.more
import com.jacekgajek.splitart.app.generated.save
import com.jacekgajek.splitart.app.generated.unarchive
import com.jacekgajek.splitart.app.ui.components.Actions
import com.jacekgajek.splitart.app.ui.components.ConfirmDialog
import com.jacekgajek.splitart.app.ui.components.DateField
import com.jacekgajek.splitart.model.Band
import com.jacekgajek.splitart.model.Event
import com.jacekgajek.splitart.ops.Change
import com.jacekgajek.splitart.ops.DeleteEvent
import com.jacekgajek.splitart.ops.SetEventArchived
import com.jacekgajek.splitart.ops.UpdateEvent
import org.jetbrains.compose.resources.stringResource

@Composable
fun EditDetailsDialog(band: Band, event: Event, actions: Actions, onDismiss: () -> Unit) {
    var fields by remember {
        mutableStateOf(
            EventFieldsState(
                name = event.name,
                date = event.date,
                location = event.location.orEmpty(),
                mapUrl = event.mapUrl,
                // Events from before agenda times get the band's template, all unset (ITERATION_2 §4.1).
                agenda = event.agenda.ifEmpty { band.effectiveAgendaTemplate.map { AgendaTime(newId(), it.kind, it.label, fromTemplate = true) } },
            ),
        )
    }
    // A place shared from Google Maps to this event while the dialog is open shows here instead of being undone on Save.
    LaunchedEffect(event.mapUrl) {
        if (event.mapUrl != null && fields.mapUrl != event.mapUrl) fields = fields.copy(location = fields.location.ifBlank { event.location.orEmpty() }, mapUrl = event.mapUrl)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { EventFields(fields) { fields = it } } },
        confirmButton = {
            TextButton(enabled = fields.name.isNotBlank(), onClick = {
                val update = UpdateEvent(
                    event.id,
                    name = Change(fields.name).takeIf { fields.name.trim() != event.name },
                    date = Change(fields.date).takeIf { fields.date != event.date },
                    location = Change(fields.location.ifBlank { null }).takeIf { fields.location.trim() != event.location.orEmpty() },
                    mapUrl = Change(fields.mapUrl).takeIf { fields.mapUrl != event.mapUrl },
                )
                if (listOf(update.name, update.date, update.location, update.mapUrl).any { it != null }) actions.run(update)
                val agenda = fields.agenda
                if (agenda != event.agenda && !(event.agenda.isEmpty() && agenda.all { it.time == null })) actions.run(SetEventAgenda(event.id, agenda))
                onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

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
