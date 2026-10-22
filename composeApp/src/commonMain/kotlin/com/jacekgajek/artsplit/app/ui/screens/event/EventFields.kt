package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.agenda_add_time
import com.jacekgajek.artsplit.app.generated.agenda_label
import com.jacekgajek.artsplit.app.generated.event_date
import com.jacekgajek.artsplit.app.generated.event_location
import com.jacekgajek.artsplit.app.generated.event_name
import com.jacekgajek.artsplit.app.generated.map_linked
import com.jacekgajek.artsplit.app.generated.map_no_link
import com.jacekgajek.artsplit.app.generated.map_paste
import com.jacekgajek.artsplit.app.generated.map_select
import com.jacekgajek.artsplit.app.platform.rememberPaste
import com.jacekgajek.artsplit.app.ui.components.DateField
import com.jacekgajek.artsplit.app.ui.components.RemoveButton
import com.jacekgajek.artsplit.app.ui.components.TextInputDialog
import com.jacekgajek.artsplit.app.ui.components.TimePickerDialog
import com.jacekgajek.artsplit.app.ui.model.displayLabel
import com.jacekgajek.artsplit.app.ui.model.formatAgendaTime
import com.jacekgajek.artsplit.domain.Places
import com.jacekgajek.artsplit.model.AgendaTime
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** What the create and edit dialogs edit about an event. */
data class EventFieldsState(
    val name: String,
    val date: LocalDate,
    val location: String,
    val mapUrl: String?,
    val agenda: List<AgendaTime>,
)

/**
 * The fields of the create and edit dialogs (ITERATION_2 §4.3, §4.4): name, date, location with Select on map and
 * Paste from Maps, and the agenda: set times as rows, the unset ones (and "+ Add time") as chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventFields(state: EventFieldsState, onState: (EventFieldsState) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val paste = rememberPaste()
    val scope = rememberCoroutineScope()
    // The paste finishes later (the browser may ask for permission), so it must apply to the fields as they are then.
    val current by rememberUpdatedState(state)
    var noLinkInClipboard by remember(state.mapUrl, state.location) { mutableStateOf(false) }
    var picking by remember { mutableStateOf<AgendaTime?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    val agenda = state.agenda
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.name, { onState(state.copy(name = it)) }, label = { Text(stringResource(Res.string.event_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DateField(state.date, { onState(state.copy(date = it)) }, stringResource(Res.string.event_date))
        OutlinedTextField(state.location, { onState(state.copy(location = it)) }, label = { Text(stringResource(Res.string.event_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Google Maps does the picking: find the place or drop a pin there, Share > Copy link, then paste here.
            AssistChip(onClick = { uriHandler.openUri(Places.searchUrl(state.location)) }, label = { Text(stringResource(Res.string.map_select)) }, leadingIcon = { Icon(Icons.Default.Map, null) })
            AssistChip(onClick = {
                scope.launch {
                    val place = paste()?.let(Places::parse)
                    if (place != null) onState(current.copy(location = current.location.ifBlank { place.name.orEmpty() }, mapUrl = place.url))
                    noLinkInClipboard = place == null
                }
            }, label = { Text(stringResource(Res.string.map_paste)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) })
        }
        state.mapUrl?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.map_linked), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                RemoveButton(stringResource(Res.string.map_linked)) { onState(state.copy(mapUrl = null)) }
            }
        }
        if (noLinkInClipboard) Text(stringResource(Res.string.map_no_link), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        agenda.filter { it.time != null }.sortedBy { it.minutesOfEventDay }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = item.displayLabel()
                Text(label, Modifier.weight(1f))
                TextButton(onClick = { picking = item }, modifier = Modifier.semantics { contentDescription = "$label ${formatAgendaTime(item)}" }) {
                    Text(formatAgendaTime(item))
                }
                // Built-in and template times go back to the chips; one-off times disappear.
                RemoveButton(label) {
                    onState(state.copy(agenda = if (item.kind != null || item.fromTemplate) agenda.map { if (it.id == item.id) it.copy(time = null) else it } else agenda - item))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            agenda.filter { it.time == null }.forEach { item ->
                AssistChip(onClick = { picking = item }, label = { Text("+ " + item.displayLabel()) })
            }
            AssistChip(onClick = { addingCustom = true }, label = { Text(stringResource(Res.string.agenda_add_time)) })
        }
    }
    picking?.let { item ->
        TimePickerDialog(item.time, item.displayLabel(), onTime = { time ->
            onState(state.copy(agenda = if (agenda.any { it.id == item.id }) agenda.map { if (it.id == item.id) it.copy(time = time) else it } else agenda + item.copy(time = time)))
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
