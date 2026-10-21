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
    val continueEvent = settings.lastEditedEventId?.let { band.event(it) }?.takeIf { !it.archived }

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
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (continueEvent != null) {
                item {
                    Card(
                        onClick = { onOpenEvent(continueEvent.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(
                                stringResource(Res.string.continue_event, continueEvent.name, formatDate(continueEvent.date, language)),
                                Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
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
            items(summaries, key = { it.event.id }) { summary -> EventRow(band, summary) { onOpenEvent(summary.event.id) } }
        }
    }

    if (creating) {
        NewEventDialog(
            onDismiss = { creating = false },
            onCreate = { name, date, location ->
                val eventId = newId()
                val ok = actions.run(
                    CreateEvent(eventId, name, date, location, participants = lineupOfLastEvent(band, state.me?.id)),
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
private fun EventRow(band: Band, summary: EventSummary, onClick: () -> Unit) {
    val event = summary.event
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(event.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(formatDate(event.date, language), event.location).joinToString(" · "),
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
