package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.ui.components.listWidth
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.jacekgajek.kapelusz.app.ui.screens.event.EventFieldsState
import com.jacekgajek.kapelusz.app.ui.screens.event.EventFields
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.selected
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.app.ui.model.isArtist
import com.jacekgajek.kapelusz.app.ui.model.setTimes
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.app.generated.artist_to_hand_back
import com.jacekgajek.kapelusz.app.generated.upcoming_event
import com.jacekgajek.kapelusz.app.ui.model.upcomingEvent
import com.jacekgajek.kapelusz.app.ui.model.unsettledEvent
import androidx.compose.material.icons.filled.Event
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.continue_event
import com.jacekgajek.kapelusz.app.generated.create
import com.jacekgajek.kapelusz.app.generated.events_empty
import com.jacekgajek.kapelusz.app.generated.events_empty_artist
import com.jacekgajek.kapelusz.app.generated.events_empty_cannot_create
import com.jacekgajek.kapelusz.app.generated.events_filtered_empty
import com.jacekgajek.kapelusz.app.generated.filter_only_mine
import com.jacekgajek.kapelusz.app.generated.filter_show_archived
import com.jacekgajek.kapelusz.app.generated.my_even
import com.jacekgajek.kapelusz.app.generated.my_get
import com.jacekgajek.kapelusz.app.generated.my_hand_over
import com.jacekgajek.kapelusz.app.generated.new_event
import com.jacekgajek.kapelusz.app.generated.new_event_lineup_hint
import com.jacekgajek.kapelusz.app.generated.tab_events
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.StatusChip
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.EventSummary
import com.jacekgajek.kapelusz.app.ui.model.MyNumber
import com.jacekgajek.kapelusz.app.ui.model.eventSummaries
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.app.ui.theme.MoneyColors
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.ops.CreateEvent
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
    // Artists see the events they play (archived ones too, with the filter) and nothing about the band's money
    // (ITERATION_4 §1.1).
    val artist = band.isArtist(state.userId)
    val summaries = eventSummaries(band, state.me, if (artist) settings.copy(onlyMyEvents = true) else settings)
    val today = today()
    val continueEvent = remember(band, today, artist) { if (artist) null else unsettledEvent(band, today) }
    // One card per event: an open gig tonight is unsettled business, not also "upcoming".
    val upcoming = remember(band, state.me, today, continueEvent, artist) {
        upcomingEvent(band, state.me, today, except = continueEvent?.id)?.takeIf { !artist || it.participant(state.me?.id ?: "") != null }
    }

    Scaffold(
        topBar = { TabTopBar(band.name.ifBlank { stringResource(Res.string.tab_events) }, onSettings, onRoster) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (Can(band, state.userId).createEvent()) ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(Res.string.new_event)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
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
                    // Artists only ever see their own events: no "only mine" to switch.
                    if (!artist) FilterChip(
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
                val text = when {
                    // Hidden by the archived filter: say so, as for others.
                    artist && band.events.any { it.participant(state.me?.id ?: "") != null } -> Res.string.events_filtered_empty
                    artist -> Res.string.events_empty_artist
                    // "Create the first one!" only to those who can.
                    band.events.isEmpty() && Can(band, state.userId).createEvent() -> Res.string.events_empty
                    band.events.isEmpty() -> Res.string.events_empty_cannot_create
                    else -> Res.string.events_filtered_empty
                }
                item { Text(stringResource(text), Modifier.padding(vertical = 32.dp)) }
            }
            items(summaries, key = { it.event.id }) { summary ->
                EventRow(band, summary, showStatus = !artist) { onOpenEvent(summary.event.id) }
            }
        }
    }

    // "New event at this place", from a place shared from Google Maps (ITERATION_2 §4.4).
    val sharedPlace by graph.pendingNewEventPlace.collectAsState()
    LaunchedEffect(sharedPlace) { if (sharedPlace != null) creating = true }
    if (creating) {
        NewEventDialog(
            band,
            initialLocation = sharedPlace?.name.orEmpty(),
            initialMapUrl = sharedPlace?.url,
            onDismiss = { creating = false; graph.pendingNewEventPlace.value = null },
            onCreate = { fields ->
                val eventId = newId()
                val ok = actions.run(
                    CreateEvent(
                        eventId, fields.name.trim(), fields.date, fields.location.ifBlank { null },
                        participants = lineupOfLastEvent(band, state.me?.id),
                        agenda = fields.agenda,
                        mapUrl = fields.mapUrl,
                    ),
                )
                if (ok) onOpenEvent(eventId)
            },
        )
    }
}

/**
 * Bands usually play with the same lineup: copy the members (not guests) of the most recent event, each with their
 * default share (ITERATION_4 §2.3).
 */
private fun lineupOfLastEvent(band: Band, myId: String?): List<Participant> {
    val last = band.events.filter { !it.archived }.maxWithOrNull(compareBy({ it.date }, { it.updatedAt }))
    val members = last?.participants
        ?.mapNotNull { band.member(it.partyId)?.takeIf { member -> !member.archived && member.role != Role.VIEWER } }
        ?.map { it.asParticipant() }
    return members ?: listOfNotNull(myId?.let { band.member(it) }?.takeIf { it.role != Role.VIEWER }?.asParticipant())
}

@Composable
private fun EventRow(band: Band, summary: EventSummary, showStatus: Boolean, onClick: () -> Unit) {
    val event = summary.event
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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
                if (showStatus) StatusChip(summary.settlement.status, event.archived)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarStack(event.participants.map { partyName(band, event, it.partyId, "") to band.member(it.partyId)?.pictureUrl }, Modifier.weight(1f))
                // A draft has no money in it yet: nothing to be settled about.
                val number = summary.myNumber
                when {
                    summary.settlement.status == EventStatus.DRAFT -> {}
                    // An artist doesn't hand money over: a lead settles what they got too much (V5-M1 C7).
                    !showStatus && number is MyNumber.HandOver -> Text(
                        stringResource(Res.string.artist_to_hand_back) + ": " + formatMoney(number.amount, band.currency, language),
                        color = MoneyColors.negative,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    else -> MyNumberText(number, band.currency)
                }
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
private fun NewEventDialog(band: Band, initialLocation: String, initialMapUrl: String?, onDismiss: () -> Unit, onCreate: (EventFieldsState) -> Unit) {
    var fields by remember {
        mutableStateOf(
            EventFieldsState(
                name = "",
                date = today(),
                location = initialLocation,
                mapUrl = initialMapUrl,
                // The band's agenda template, to fill in now or later (ITERATION_2 §4.3).
                agenda = band.effectiveAgendaTemplate.map { AgendaTime(newId(), it.kind, it.label, fromTemplate = true) },
            ),
        )
    }
    // A place shared from Google Maps while the dialog is already open.
    LaunchedEffect(initialMapUrl) {
        if (initialMapUrl != null && fields.mapUrl != initialMapUrl) fields = fields.copy(location = fields.location.ifBlank { initialLocation }, mapUrl = initialMapUrl)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_event)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EventFields(fields) { fields = it }
                Text(stringResource(Res.string.new_event_lineup_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = fields.name.isNotBlank(), onClick = { onDismiss(); onCreate(fields) }) {
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

/**
 * Everyone's avatars in the room left beside the card's text (ITERATION_5 §3): the same size, overlapping more as room
 * runs out; when even then a third of each would not show, the first ones and "+N" for the rest.
 */
@Composable
private fun AvatarStack(people: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    BoxWithConstraints(modifier) {
        val size = AVATAR_SIZE
        val loose = size - 6.dp
        val tightest = size / 3
        fun step(count: Int) = if (count <= 1) loose else ((maxWidth - size) / (count - 1)).coerceAtMost(loose)
        val all = step(people.size) >= tightest
        // Faces and a "+N" bubble, all at the tightest step.
        val shown = if (all) people.size else ((maxWidth - size) / tightest).toInt().coerceIn(1, people.size - 1)
        val step = if (all) step(people.size) else tightest
        Row(horizontalArrangement = Arrangement.spacedBy(step - size)) {
            // Exactly their size: a row short of room would otherwise squash the last one into an oval.
            people.take(shown).forEach { (name, picture) -> Box(Modifier.requiredSize(size)) { Avatar(name, picture, size) } }
            if (!all) {
                Box(
                    Modifier.requiredSize(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Text("+${people.size - shown}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

private val AVATAR_SIZE = 28.dp
