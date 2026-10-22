package com.jacekgajek.kapelusz.app.ui.screens.event

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
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.app.ui.model.displayLabel
import com.jacekgajek.kapelusz.app.ui.components.TimePickerDialog
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.generated.agenda_label
import com.jacekgajek.kapelusz.app.generated.agenda_add_time
import com.jacekgajek.kapelusz.app.data.newId
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.archive
import com.jacekgajek.kapelusz.app.generated.archive_not_settled
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.delete_event_confirm
import com.jacekgajek.kapelusz.app.generated.delete_event_title
import com.jacekgajek.kapelusz.app.generated.edit_details
import com.jacekgajek.kapelusz.app.generated.event_date
import com.jacekgajek.kapelusz.app.generated.event_location
import com.jacekgajek.kapelusz.app.generated.event_name
import com.jacekgajek.kapelusz.app.generated.more
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.unarchive
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.ConfirmDialog
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.domain.Settlement
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.DeleteEvent
import com.jacekgajek.kapelusz.ops.SetEventArchived
import com.jacekgajek.kapelusz.ops.UpdateEvent
import org.jetbrains.compose.resources.stringResource

@Composable
fun EditDetailsDialog(band: Band, event: Event, actions: Actions, onDismiss: () -> Unit) {
    // Compared with the event as it was when the dialog opened: only what the user changed is sent (ITERATION_3 R8).
    val opened = remember { event }
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
                    name = Change(fields.name).takeIf { fields.name.trim() != opened.name },
                    date = Change(fields.date).takeIf { fields.date != opened.date },
                    location = Change(fields.location.ifBlank { null }).takeIf { fields.location.trim() != opened.location.orEmpty() },
                    mapUrl = Change(fields.mapUrl).takeIf { fields.mapUrl != opened.mapUrl },
                )
                var saved = true
                if (listOf(update.name, update.date, update.location, update.mapUrl).any { it != null }) saved = actions.run(update)
                val agenda = fields.agenda
                if (agenda != opened.agenda && !(opened.agenda.isEmpty() && agenda.all { it.time == null })) saved = actions.run(SetEventAgenda(event.id, agenda)) && saved
                // Refused (the snackbar says why): the dialog stays, with what the user typed.
                if (saved) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Overflow menu: archive (only settled events and drafts) / restore, delete (only events without recorded transfers
 * can be deleted).
 */
@Composable
fun EventMenu(event: Event, settlement: Settlement, actions: Actions, onDeleted: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(Res.string.more)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val canArchive = event.archived || settlement.canArchive
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(if (event.archived) Res.string.unarchive else Res.string.archive))
                    if (!canArchive) Text(stringResource(Res.string.archive_not_settled), style = MaterialTheme.typography.bodySmall)
                }
            },
            enabled = canArchive,
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
