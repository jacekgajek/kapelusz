package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.agenda_add_time
import com.jacekgajek.kapelusz.app.generated.agenda_label
import com.jacekgajek.kapelusz.app.generated.event_date
import com.jacekgajek.kapelusz.app.generated.event_location
import com.jacekgajek.kapelusz.app.generated.event_name
import com.jacekgajek.kapelusz.app.generated.location_clear
import com.jacekgajek.kapelusz.app.generated.location_pick_map
import com.jacekgajek.kapelusz.app.generated.map_no_link
import com.jacekgajek.kapelusz.app.generated.map_paste
import com.jacekgajek.kapelusz.app.generated.map_reading
import com.jacekgajek.kapelusz.app.generated.map_unreadable
import com.jacekgajek.kapelusz.app.generated.places_powered_by_google
import com.jacekgajek.kapelusz.app.platform.MapPickerDialog
import com.jacekgajek.kapelusz.app.platform.mapPickerAvailable
import com.jacekgajek.kapelusz.app.platform.rememberPaste
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.RemoveButton
import com.jacekgajek.kapelusz.app.ui.components.TextInputDialog
import com.jacekgajek.kapelusz.app.ui.components.TimePickerDialog
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.displayLabel
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * Google's place services for Location (ITERATION_5 §9), through the server, in the app's language. Each call throws
 * when it cannot (offline, not set up). A search and the details of the place picked from it are one Google session.
 */
class PlaceLookup(private val api: ApiClient, private val bandId: String, private val language: String) {
    private var session = newId()

    suspend fun suggest(query: String): List<PlaceSuggestion> = api.places(bandId, query, language, session)

    /** The picked place's name and full address; a new search is a new session. */
    suspend fun details(place: PlaceSuggestion): String = try {
        api.placeText(bandId, place.placeId, language, session)
    } finally {
        session = newId()
    }

    /** A Google Maps link read: the place and its address, or a pin's coordinates. */
    suspend fun resolve(url: String): String = api.resolveMapLink(bandId, url, language)
}

/** Place lookups for the band's event dialogs; null for who cannot edit events. */
@Composable
fun rememberPlaceLookup(api: ApiClient, band: Band, userId: String): PlaceLookup? {
    val lang = language
    val allowed = band.memberOfUser(userId)?.role?.atLeast(Role.LEAD) == true
    return remember(api, band.id, lang, allowed) { if (allowed) PlaceLookup(api, band.id, lang) else null }
}

/** What the create and edit dialogs edit about an event. */
data class EventFieldsState(
    val name: String,
    val date: LocalDate,
    val location: String,
    val agenda: List<AgendaTime>,
)

/**
 * The fields of the create and edit dialogs (ITERATION_2 §4.3, §4.4): name, date, location with Paste from Maps, and
 * the agenda: set times as rows, the unset ones (and "+ Add time") as chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventFields(state: EventFieldsState, places: PlaceLookup?, onState: (EventFieldsState) -> Unit) {
    val paste = rememberPaste()
    val scope = rememberCoroutineScope()
    // The paste finishes later (the browser may ask for permission), so it must apply to the fields as they are then.
    val current by rememberUpdatedState(state)
    // What the paste chip last did: nothing, reading a link, no link in the clipboard, or a link that couldn't be read.
    var pasting by remember { mutableStateOf<Pasting?>(null) }
    var picking by remember { mutableStateOf<AgendaTime?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    val agenda = state.agenda
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.name, { onState(state.copy(name = it)) }, label = { Text(stringResource(Res.string.event_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DateField(state.date, { onState(state.copy(date = it)) }, stringResource(Res.string.event_date))
        LocationField(state, places, onState)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A link copied in Maps (Share › Copy link) is pasted here (ITERATION_6 §1: Kapelusz takes no shared text).
            AssistChip(onClick = {
                scope.launch {
                    val place = paste()?.let(Places::parse)
                    if (place == null) {
                        pasting = Pasting.NoLink
                        return@launch
                    }
                    // Read by the server (ITERATION_5 §9.1): the place's name and address, or the pin's coordinates.
                    pasting = Pasting.Reading
                    val before = current.location
                    val text = places?.let { runSuspendCatching { it.resolve(place.url) }.getOrNull() }
                    pasting = if (text == null && place.name == null) Pasting.Unreadable else null
                    // Typed over meanwhile: left as typed.
                    if (current.location == before) (text ?: place.name)?.let { onState(current.copy(location = it)) }
                }
            }, label = { Text(stringResource(Res.string.map_paste)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) })
        }
        when (pasting) {
            Pasting.Reading -> Text(stringResource(Res.string.map_reading), style = MaterialTheme.typography.bodySmall)
            Pasting.NoLink -> Text(stringResource(Res.string.map_no_link), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Pasting.Unreadable -> Text(stringResource(Res.string.map_unreadable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> {}
        }
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
 * under the field. Picking one puts in its name and full address; typing freely still works, and offline (or without
 * place search on the server) the field is plain text.
 */
@Composable
private fun LocationField(state: EventFieldsState, places: PlaceLookup?, onState: (EventFieldsState) -> Unit) {
    // Only what the user types is searched: not the text the dialog opened with, nor a picked place.
    var typed by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<PlaceSuggestion>()) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(state)
    LaunchedEffect(typed) {
        val query = typed?.trim()
        if (places == null || query == null || query.length < Places.MIN_QUERY) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        suggestions = runSuspendCatching { places.suggest(query.take(Places.MAX_QUERY)) }.getOrDefault(emptyList())
    }
    // Set from elsewhere (a picked place, then its full address; a pasted link): the cursor goes to its end, not where
    // the typing stopped.
    var field by remember { mutableStateOf(TextFieldValue(state.location, TextRange(state.location.length))) }
    if (field.text != state.location) field = TextFieldValue(state.location, TextRange(state.location.length))
    // One line of text that wraps when long (a full address), so all of it shows; Enter adds no new line.
    OutlinedTextField(
        field,
        {
            val text = it.text.replace("\n", " ")
            // Against the field, not the last composition's state: two edits can come before it is composed again.
            val changed = text != field.text
            field = it.copy(text = text)
            if (changed) {
                typed = text
                onState(current.copy(location = text))
            }
        },
        label = { Text(stringResource(Res.string.event_location)) },
        trailingIcon = if (state.location.isNotEmpty() || mapPickerAvailable) {
            {
                Row {
                    // Any spot, also one without an address (ITERATION_6 §3).
                    if (mapPickerAvailable) IconButton(onClick = { picking = true }) { Icon(Icons.Default.Place, stringResource(Res.string.location_pick_map)) }
                    if (state.location.isNotEmpty()) {
                        IconButton(onClick = {
                            typed = null
                            onState(state.copy(location = ""))
                        }) { Icon(Icons.Default.Clear, stringResource(Res.string.location_clear)) }
                    }
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    if (picking) {
        MapPickerDialog(
            start = Places.pinIn(state.location),
            onPick = { latitude, longitude, name ->
                picking = false
                typed = null
                onState(current.copy(location = Places.pinText(latitude, longitude, name)))
            },
            onDismiss = { picking = false },
        )
    }
    if (suggestions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            suggestions.forEach { place ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        typed = null
                        // The name and town at once; the full address when the server has it, unless edited meanwhile.
                        val short = Places.label(place)
                        onState(state.copy(location = short))
                        if (places != null) {
                            scope.launch {
                                val full = runSuspendCatching { places.details(place) }.getOrNull()
                                if (full != null && current.location == short) onState(current.copy(location = full))
                            }
                        }
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

/** The paste chip's state. */
private enum class Pasting { Reading, NoLink, Unreadable }
