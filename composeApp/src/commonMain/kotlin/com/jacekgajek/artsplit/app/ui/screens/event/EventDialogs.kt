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
import com.jacekgajek.artsplit.app.generated.event_notes
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

@Composable
fun EditDetailsDialog(event: Event, actions: Actions, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(event.name) }
    var date by remember { mutableStateOf(event.date) }
    var location by remember { mutableStateOf(event.location.orEmpty()) }
    var notes by remember { mutableStateOf(event.notes.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.event_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(Res.string.event_date))
                OutlinedTextField(location, { location = it }, label = { Text(stringResource(Res.string.event_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(Res.string.event_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val update = UpdateEvent(
                    event.id,
                    name = Change(name).takeIf { name.trim() != event.name },
                    date = Change(date).takeIf { date != event.date },
                    location = Change(location.ifBlank { null }).takeIf { location.trim() != event.location.orEmpty() },
                    notes = Change(notes.ifBlank { null }).takeIf { notes.trim() != event.notes.orEmpty() },
                )
                if (listOf(update.name, update.date, update.location, update.notes).any { it != null }) actions.run(update)
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
