package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.pluralStringResource
import com.jacekgajek.artsplit.app.generated.events_restored
import com.jacekgajek.artsplit.app.generated.events_archived
import com.jacekgajek.artsplit.app.generated.selected_count
import com.jacekgajek.artsplit.app.generated.select_all
import com.jacekgajek.artsplit.app.generated.select_events
import com.jacekgajek.artsplit.app.generated.undo
import com.jacekgajek.artsplit.app.generated.unarchive
import com.jacekgajek.artsplit.app.generated.archive
import com.jacekgajek.artsplit.ops.SetEventArchived
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.app.ui.model.setTimes
import com.jacekgajek.artsplit.app.ui.model.formatAgendaTime
import com.jacekgajek.artsplit.model.effectiveAgendaTemplate
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import com.jacekgajek.artsplit.app.generated.upcoming_event
import com.jacekgajek.artsplit.app.ui.model.upcomingEvent
import com.jacekgajek.artsplit.app.ui.model.unsettledEvent
import androidx.compose.material.icons.filled.Event
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.AppSettings
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.data.today
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.continue_event
import com.jacekgajek.artsplit.app.generated.create
import com.jacekgajek.artsplit.app.generated.event_date
import com.jacekgajek.artsplit.app.generated.event_location
import com.jacekgajek.artsplit.app.generated.event_name
import com.jacekgajek.artsplit.app.generated.events_empty
import com.jacekgajek.artsplit.app.generated.events_filtered_empty
import com.jacekgajek.artsplit.app.generated.filter_only_mine
import com.jacekgajek.artsplit.app.generated.filter_show_archived
import com.jacekgajek.artsplit.app.generated.my_even
import com.jacekgajek.artsplit.app.generated.my_get
import com.jacekgajek.artsplit.app.generated.my_hand_over
import com.jacekgajek.artsplit.app.generated.new_event
import com.jacekgajek.artsplit.app.generated.new_event_lineup_hint
import com.jacekgajek.artsplit.app.generated.tab_events
import com.jacekgajek.artsplit.app.ui.components.Avatar
import com.jacekgajek.artsplit.app.ui.components.DateField
import com.jacekgajek.artsplit.app.ui.components.StatusChip
import com.jacekgajek.artsplit.app.ui.components.TabTopBar
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.components.rememberActions
import com.jacekgajek.artsplit.app.ui.model.Can
import com.jacekgajek.artsplit.app.ui.model.EventSummary
import com.jacekgajek.artsplit.app.ui.model.MyNumber
import com.jacekgajek.artsplit.app.ui.model.eventSummaries
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.partyName
import com.jacekgajek.artsplit.app.ui.theme.MoneyColors
import com.jacekgajek.artsplit.model.Band
import com.jacekgajek.artsplit.model.Participant
import com.jacekgajek.artsplit.ops.CreateEvent
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

@Composable
fun EventsScreen(
    graph: AppGraph,
    state: BandState.Ready,
    settings: AppSettings,
    onOpenEvent: (String) -> Unit,
    onSettings: () -> Unit,
    onRoster: () -> Unit,
) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    var creating by remember { mutableStateOf(false) }
    val summaries = eventSummaries(band, state.me, settings)
    val today = today()
    val continueEvent = remember(band, today) { unsettledEvent(band, today) }
    // One card per event: an open gig tonight is unsettled business, not also "upcoming".
    val upcoming = remember(band, state.me, today, continueEvent) { upcomingEvent(band, state.me, today, except = continueEvent?.id) }

    // Bulk archive (VERSION_2 §3.3): null when not selecting; per band. Leads and above only, like one event.
    var selection by rememberSaveable(band.id) { mutableStateOf<Set<String>?>(null) }
    val canArchive = Can(band, state.userId).archiveEvents()
    // Only events on the list count: a filter may have hidden some selected ones since.
    val visibleIds = summaries.map { it.event.id }.toSet()
    val chosen = selection.orEmpty().filter { it in visibleIds }.mapNotNull { band.event(it) }
    // Back leaves selection mode instead of the screen.
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = selection != null) { selection = null }
    var archivedNotice by remember { mutableStateOf<ArchiveNotice?>(null) }
    fun archiveSelected(archive: Boolean) {
        // Only what was actually applied is reported and undone (a change can be refused, e.g. a role changed).
        val changed = chosen.filter { it.archived != archive }.filter { actions.run(SetEventArchived(it.id, archive)) }.map { it.id }
        selection = null
        if (changed.isNotEmpty()) archivedNotice = ArchiveNotice(changed, archive)
    }
    archivedNotice?.let { notice ->
        // Resolved here, in the app's language and with proper plural forms.
        val message = pluralStringResource(if (notice.archive) Res.plurals.events_archived else Res.plurals.events_restored, notice.ids.size, notice.ids.size)
        val undo = stringResource(Res.string.undo)
        LaunchedEffect(notice) {
            if (snackbar.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                notice.ids.forEach { actions.run(SetEventArchived(it, !notice.archive)) }
            }
            archivedNotice = null
        }
    }

    Scaffold(
        topBar = {
            if (selection != null) {
                SelectionTopBar(
                    count = chosen.size,
                    onClose = { selection = null },
                    onSelectAll = { selection = visibleIds },
                    restore = chosen.isNotEmpty() && chosen.all { it.archived },
                    onArchive = ::archiveSelected,
                )
            } else {
                TabTopBar(band.name.ifBlank { stringResource(Res.string.tab_events) }, onSettings, onRoster) {
                    // Long-press selects on phones; with a mouse this is the natural way in.
                    if (canArchive && summaries.isNotEmpty()) {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Checklist, stringResource(Res.string.select_events)) }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selection == null && Can(band, state.userId).createEvent()) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.new_event)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (continueEvent != null) {
                item {
                    EventCard(
                        Icons.Default.PlayArrow,
                        stringResource(Res.string.continue_event, continueEvent.name, formatDate(continueEvent.date, language)),
                        MaterialTheme.colorScheme.primaryContainer,
                    ) { onOpenEvent(continueEvent.id) }
                }
            }
            if (upcoming != null) {
                item {
                    EventCard(
                        Icons.Default.Event,
                        stringResource(Res.string.upcoming_event, upcoming.name, formatDate(upcoming.date, language)),
                        MaterialTheme.colorScheme.secondaryContainer,
                    ) { onOpenEvent(upcoming.id) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.onlyMyEvents,
                        onClick = { graph.settings.update { it.copy(onlyMyEvents = !it.onlyMyEvents) } },
                        label = { Text(stringResource(Res.string.filter_only_mine)) },
                    )
                    FilterChip(
                        selected = settings.showArchived,
                        onClick = { graph.settings.update { it.copy(showArchived = !it.showArchived) } },
                        label = { Text(stringResource(Res.string.filter_show_archived)) },
                    )
                }
            }
            if (summaries.isEmpty()) {
                // Events hidden by the filters are not "no events": say which it is.
                val text = if (band.events.isEmpty()) Res.string.events_empty else Res.string.events_filtered_empty
                item { Text(stringResource(text), Modifier.padding(vertical = 32.dp)) }
            }
            items(summaries, key = { it.event.id }) { summary ->
                val id = summary.event.id
                val selected = selection
                EventRow(
                    band,
                    summary,
                    selected = selected?.contains(id),
                    onLongClick = { selection = (selected ?: emptySet()) + id }.takeIf { canArchive },
                ) {
                    if (selected != null) selection = if (id in selected) selected - id else selected + id else onOpenEvent(id)
                }
            }
        }
    }

    if (creating) {
        NewEventDialog(
            onDismiss = { creating = false },
            onCreate = { name, date, location ->
                val eventId = newId()
                val ok = actions.run(
                    CreateEvent(
                        eventId, name, date, location,
                        participants = lineupOfLastEvent(band, state.me?.id),
                        // The band's agenda template, times still to be set (VERSION_2 §4.1).
                        agenda = band.effectiveAgendaTemplate.map { AgendaTime(newId(), it.kind, it.label) },
                    ),
                )
                if (ok) onOpenEvent(eventId)
            },
        )
    }
}

/** Bands usually play with the same lineup: copy the members (not guests) of the most recent event. */
private fun lineupOfLastEvent(band: Band, myId: String?): List<Participant> {
    val last = band.events.filter { !it.archived }.maxWithOrNull(compareBy({ it.date }, { it.updatedAt }))
    val members = last?.participants
        ?.filter { band.member(it.partyId)?.archived == false }
        ?.map { Participant(it.partyId) }
    return members ?: listOfNotNull(myId?.let { Participant(it) })
}

@Composable
private fun EventRow(band: Band, summary: EventSummary, selected: Boolean?, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    val event = summary.event
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        colors = if (selected == true) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
        border = if (selected == true) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(event.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(formatDate(event.date, language), event.setTimes().firstOrNull()?.let(::formatAgendaTime), event.location)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusChip(summary.settlement.status, event.archived)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    event.participants.take(8).forEach { Avatar(partyName(band, event, it.partyId, "")) }
                }
                MyNumberText(summary.myNumber, band.currency)
            }
        }
    }
}

@Composable
private fun MyNumberText(number: MyNumber, currency: String) {
    when (number) {
        is MyNumber.Get -> Text(
            stringResource(Res.string.my_get, formatMoney(number.amount, currency, language)),
            color = MoneyColors.positive,
            style = MaterialTheme.typography.labelLarge,
        )
        is MyNumber.HandOver -> Text(
            stringResource(Res.string.my_hand_over, formatMoney(number.amount, currency, language)),
            color = MoneyColors.negative,
            style = MaterialTheme.typography.labelLarge,
        )
        MyNumber.Even -> Text(stringResource(Res.string.my_even), style = MaterialTheme.typography.labelLarge)
        MyNumber.NotParticipating -> {}
    }
}

@Composable
private fun NewEventDialog(onDismiss: () -> Unit, onCreate: (String, LocalDate, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_event)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.event_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(Res.string.event_date))
                OutlinedTextField(location, { location = it }, label = { Text(stringResource(Res.string.event_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.new_event_lineup_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDismiss(); onCreate(name, date, location.ifBlank { null }) }) {
                Text(stringResource(Res.string.create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A compact one-line shortcut card above the list ("Continue", "Upcoming"). */
@Composable
private fun EventCard(icon: ImageVector, text: String, color: Color, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The top bar while selecting events: how many, select all, and archive (or restore when only archived are chosen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, restore: Boolean, onArchive: (Boolean) -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(Res.string.cancel)) } },
        title = { Text(pluralStringResource(Res.plurals.selected_count, count, count)) },
        actions = {
            TextButton(onClick = onSelectAll) { Text(stringResource(Res.string.select_all)) }
            TextButton(onClick = { onArchive(!restore) }, enabled = count > 0) {
                Text(stringResource(if (restore) Res.string.unarchive else Res.string.archive))
            }
        },
    )
}

/** The events just archived (or restored), for the snackbar and its Undo. */
private data class ArchiveNotice(val ids: List<String>, val archive: Boolean)
