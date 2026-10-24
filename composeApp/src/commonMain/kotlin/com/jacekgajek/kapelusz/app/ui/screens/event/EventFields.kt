package com.jacekgajek.kapelusz.app.ui.screens.event

import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.generated.places_powered_by_google
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.agenda_add_time
import com.jacekgajek.kapelusz.app.generated.agenda_label
import com.jacekgajek.kapelusz.app.generated.event_date
import com.jacekgajek.kapelusz.app.generated.event_location
import com.jacekgajek.kapelusz.app.generated.event_name
import com.jacekgajek.kapelusz.app.generated.map_linked
import com.jacekgajek.kapelusz.app.generated.map_no_link
import com.jacekgajek.kapelusz.app.generated.map_paste
import com.jacekgajek.kapelusz.app.platform.rememberPaste
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.TimePickerDialog
import com.jacekgajek.kapelusz.app.ui.model.displayLabel
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.model.AgendaTime
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** Asks for places matching what was typed in Location (ITERATION_5 §9); throws when it cannot (offline, not set up). */
typealias PlaceSearch = suspend (query: String) -> List<PlaceSuggestion>

/** Place search for the band's event dialogs, in the app's language; null for who cannot edit events. */
@Composable
fun rememberPlaceSearch(graph: AppGraph, band: Band, userId: String): PlaceSearch? {
    val lang = language
    val allowed = band.memberOfUser(userId)?.role?.atLeast(Role.LEAD) == true
    return remember(graph, band.id, lang, allowed) { if (allowed) { query -> graph.api.places(band.id, query, lang) } else null }
}

/** What the create and edit dialogs edit about an event. */
data class EventFieldsState(
    val name: String,
    val date: LocalDate,
    val location: String,
    val mapUrl: String?,
    val agenda: List<AgendaTime>,
)

/**
 * The fields of the create and edit dialogs (ITERATION_2 §4.3, §4.4): name, date, location with Paste from Maps, and
 * the agenda: set times as rows, the unset ones (and "+ Add time") as chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventFields(state: EventFieldsState, placeSearch: PlaceSearch?, onState: (EventFieldsState) -> Unit) {
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
        LocationField(state, placeSearch, onState)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // No "Select on map": coming back from Google Maps with Share › Kapelusz opens the event picker, where an event
            // still being created is not. A link copied in Maps (Share › Copy link) is pasted here instead.
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

/**
 * Location, with Google's places for what is typed (ITERATION_5 §9): from 3 characters, after a short pause, up to 5
 * under the field. Picking one puts in its name and town and links its exact pin; typing freely still works, and
 * offline (or without place search on the server) the field is plain text.
 */
@Composable
private fun LocationField(state: EventFieldsState, placeSearch: PlaceSearch?, onState: (EventFieldsState) -> Unit) {
    // Only what the user types is searched: not the text the dialog opened with, nor a picked place.
    var typed by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<PlaceSuggestion>()) }
    // A pin set otherwise (Paste from Maps, shared from Maps) ends the search: a pick would replace it.
    LaunchedEffect(state.mapUrl) { typed = null }
    LaunchedEffect(typed) {
        val query = typed?.trim()
        if (placeSearch == null || query == null || query.length < Places.MIN_QUERY) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        suggestions = runSuspendCatching { placeSearch(query.take(Places.MAX_QUERY)) }.getOrDefault(emptyList())
    }
    OutlinedTextField(
        state.location,
        {
            typed = it
            onState(state.copy(location = it))
        },
        label = { Text(stringResource(Res.string.event_location)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (suggestions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            suggestions.forEach { place ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        typed = null
                        onState(state.copy(location = Places.label(place), mapUrl = Places.mapUrl(place)))
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    place.address?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // Required by Google wherever its suggestions show without its map.
            Text(
                stringResource(Res.string.places_powered_by_google),
                Modifier.align(Alignment.End).padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How long typing pauses before places are asked for, so not every keystroke is a request. */
private const val SEARCH_PAUSE_MS = 500L
