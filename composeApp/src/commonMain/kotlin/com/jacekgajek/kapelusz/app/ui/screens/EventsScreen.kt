package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.Changes
import com.jacekgajek.kapelusz.app.data.SettingsRepository
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.artist_to_hand_back
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
import com.jacekgajek.kapelusz.app.generated.search_close
import com.jacekgajek.kapelusz.app.generated.search_events
import com.jacekgajek.kapelusz.app.generated.search_no_results
import com.jacekgajek.kapelusz.app.generated.search_placeholder
import com.jacekgajek.kapelusz.app.generated.tab_events
import com.jacekgajek.kapelusz.app.generated.upcoming_event
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.BandDeletionNotice
import com.jacekgajek.kapelusz.app.ui.components.StatusChip
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.setTimes
import com.jacekgajek.kapelusz.app.ui.screens.event.EventFields
import com.jacekgajek.kapelusz.app.ui.screens.event.EventFieldsState
import com.jacekgajek.kapelusz.app.ui.screens.event.PlaceLookup
import com.jacekgajek.kapelusz.app.ui.screens.event.rememberPlaceLookup
import com.jacekgajek.kapelusz.app.ui.theme.MoneyColors
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.SettleTogether
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.ops.CancelBandDeletion
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.rules.isArtist
import com.jacekgajek.kapelusz.view.EventSearch
import com.jacekgajek.kapelusz.view.EventSummary
import com.jacekgajek.kapelusz.view.MyNumber
import com.jacekgajek.kapelusz.view.eventSummaries
import com.jacekgajek.kapelusz.view.partyName
import com.jacekgajek.kapelusz.view.searchWords
import com.jacekgajek.kapelusz.view.searchableSummaries
import com.jacekgajek.kapelusz.view.unsettledEvent
import com.jacekgajek.kapelusz.view.upcomingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EventsScreen(
    changes: Changes,
    api: ApiClient,
    preferences: SettingsRepository,
    state: BandState.Ready,
    settings: AppSettings,
    onOpenEvent: (String) -> Unit,
    onSettings: () -> Unit,
    onRoster: () -> Unit,
) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(changes, snackbar)
    var creating by remember { mutableStateOf(false) }
    // Artists see the events they play (archived ones too, with the filter) and nothing about the band's money
    // (ITERATION_4 §1.1).
    val artist = band.isArtist(state.userId)
    val onlyMine = artist || settings.onlyMyEvents
    // Settling every event is costly: again only when the band or a filter changes, not on each redraw (e.g. a key
    // typed in the search).
    val summaries = remember(band, state.me, onlyMine, settings.showArchived) {
        eventSummaries(state.view, state.me, settings.showArchived, onlyMine)
    }
    // Searching (ITERATION_6 §6): all events the user may see, whatever the filters, prepared in the background once
    // the search opens; kept until the band changes, then built again (the old results shown meanwhile).
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf<EventSearch?>(null) }
    LaunchedEffect(searching, band, state.me, artist) {
        if (!searching) {
            search = null
            return@LaunchedEffect
        }
        val me = state.me
        search = withContext(Dispatchers.Default) { EventSearch(searchableSummaries(state.view, me, artist) { yield() }) }
    }
    val words = remember(query) { searchWords(query) }
    // Until something is typed, the list as it was, filters included. Results of a search being built again leave out
    // events deleted meanwhile.
    val found = remember(search, words) { search?.find(words) }
    val results = if (searching && words.isNotEmpty()) found?.filter { band.event(it.event.id) != null } else summaries
    // The keyboard only when the search icon is tapped, not each time the row comes back (scrolling, coming back).
    var focusSearch by remember { mutableStateOf(false) }
    fun closeSearch() {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching) { closeSearch() }
    val today = today()
    val continueEvent = remember(band, today, artist) { if (artist) null else unsettledEvent(state.view, today) }
    // One card per event: an open gig tonight is unsettled business, not also "upcoming".
    val upcoming = remember(band, state.me, today, continueEvent, artist) {
        upcomingEvent(band, state.me, today, except = continueEvent?.id)?.takeIf { !artist || it.participant(state.me?.id ?: "") != null }
    }

    Scaffold(
        topBar = { TabTopBar(band.name.ifBlank { stringResource(Res.string.tab_events) }, onSettings, onRoster) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (Capabilities(band, state.userId).createEvent()) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(Res.string.new_event)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The band is to be deleted (ITERATION_7 §9): said to everyone, kept by a leader.
            if (!searching && band.deletion != null) {
                item(key = "deletion") {
                    BandDeletionNotice(
                        band,
                        {
                            actions.run(CancelBandDeletion)
                            Unit
                        }.takeIf { Capabilities(band, state.userId).allowed(CancelBandDeletion) },
                    )
                }
            }
            // What the band still lacks, for its leaders (ITERATION_5 §11): until it's there, or (no one in the app) hidden.
            // While searching, only the search and its results: the cards would push them under the keyboard.
            if (!searching) {
                bandHint(band, state.me, hiddenNoApp = band.id in settings.hiddenNoAppHints)?.let { hint ->
                    item(key = "band-hint") {
                        BandHintCard(hint, onRoster, onHide = { preferences.update { it.copy(hiddenNoAppHints = it.hiddenNoAppHints + band.id) } })
                    }
                }
            }
            // Until answered (ITERATION_5 §7): there are no pushes, and nobody visits the roster to check.
            if (!searching && state.me?.let { SettleTogether.inviterOf(band, it.id) } != null) {
                item(key = "settle-together") { SettleTogetherInvitationCard(band, state.me, actions) }
            }
            if (!searching && continueEvent != null) {
                item {
                    EventCard(
                        Icons.Default.PlayArrow,
                        stringResource(Res.string.continue_event, continueEvent.name, formatDate(continueEvent.date, language)),
                        MaterialTheme.colorScheme.primaryContainer,
                    ) { onOpenEvent(continueEvent.id) }
                }
            }
            if (!searching && upcoming != null) {
                item {
                    EventCard(
                        Icons.Default.Event,
                        stringResource(Res.string.upcoming_event, upcoming.name, formatDate(upcoming.date, language)),
                        MaterialTheme.colorScheme.secondaryContainer,
                    ) { onOpenEvent(upcoming.id) }
                }
            }
            item(key = "filters") {
                if (searching) {
                    SearchField(
                        query,
                        building = search == null && words.isNotEmpty(),
                        focus = focusSearch,
                        onFocused = { focusSearch = false },
                        onQuery = { query = it },
                        onClose = ::closeSearch,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            searching = true
                            focusSearch = true
                        }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.search_events))
                        }
                        // Artists only ever see their own events: no "only mine" to switch.
                        if (!artist) {
                            FilterChip(
                                selected = settings.onlyMyEvents,
                                onClick = { preferences.update { it.copy(onlyMyEvents = !it.onlyMyEvents) } },
                                label = { Text(stringResource(Res.string.filter_only_mine)) },
                            )
                        }
                        FilterChip(
                            selected = settings.showArchived,
                            onClick = { preferences.update { it.copy(showArchived = !it.showArchived) } },
                            label = { Text(stringResource(Res.string.filter_show_archived)) },
                        )
                    }
                }
            }
            if (searching && words.isNotEmpty()) {
                if (results?.isEmpty() == true) item { Text(stringResource(Res.string.search_no_results, query.trim()), Modifier.padding(vertical = 32.dp)) }
            } else if (summaries.isEmpty()) {
                // Events hidden by the filters are not "no events": say which it is.
                val text = when {
                    // Hidden by the archived filter: say so, as for others.
                    artist && band.events.any { it.participant(state.me?.id ?: "") != null } -> Res.string.events_filtered_empty

                    artist -> Res.string.events_empty_artist

                    // "Create the first one!" only to those who can.
                    band.events.isEmpty() && Capabilities(band, state.userId).createEvent() -> Res.string.events_empty

                    band.events.isEmpty() -> Res.string.events_empty_cannot_create

                    else -> Res.string.events_filtered_empty
                }
                item { Text(stringResource(text), Modifier.padding(vertical = 32.dp)) }
            }
            items(results.orEmpty(), key = { it.event.id }) { summary ->
                EventRow(band, summary, showStatus = !artist) { onOpenEvent(summary.event.id) }
            }
        }
    }

    if (creating) {
        NewEventDialog(
            band,
            places = rememberPlaceLookup(api, band, state.userId),
            onDismiss = { creating = false },
            onCreate = { fields ->
                val eventId = newId()
                val ok = actions.run(
                    CreateEvent(
                        eventId,
                        fields.name.trim(),
                        fields.date,
                        fields.location.ifBlank { null },
                        // Nobody yet (ITERATION_8 §1.2a): the event offers another event's line-up, or one by one.
                        agenda = fields.agenda,
                        // The band's fund default (ITERATION_7 §5.2), while the fund is on.
                        fund = band.fundDefault.takeIf { band.treasurerMemberId != null },
                    ),
                )
                if (ok) onOpenEvent(eventId)
            },
        )
    }
}

/** The search box in place of the filters: a spinner while the search is being prepared, the X clears and closes. */
@Composable
private fun SearchField(query: String, building: Boolean, focus: Boolean, onFocused: () -> Unit, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().focusRequester(requester).onPreviewKeyEvent {
            // Esc on the web, as Back elsewhere.
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) onClose()
            it.key == Key.Escape
        },
        placeholder = { Text(stringResource(Res.string.search_placeholder)) },
        leadingIcon = {
            if (building) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.search_close)) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Results show as you type: the key just puts the keyboard away, to look through them.
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
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
                AvatarStack(SettlementCalculator.partyOrder(band, event).map { partyName(band, event, it, "") to band.member(it)?.pictureUrl }, Modifier.weight(1f))
                // A draft has no money in it yet: nothing to be settled about.
                val number = summary.myNumber
                when {
                    summary.settlement.status == EventStatus.DRAFT -> {}

                    // An artist holds no money: only what they got too much is theirs to hand back (V5-M1 C7, ITERATION_7 §3.2).
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
private fun NewEventDialog(band: Band, places: PlaceLookup?, onDismiss: () -> Unit, onCreate: (EventFieldsState) -> Unit) {
    var fields by remember {
        mutableStateOf(
            EventFieldsState(
                name = "",
                date = today(),
                location = "",
                // The band's agenda template, to fill in now or later (ITERATION_2 §4.3).
                agenda = band.effectiveAgendaTemplate.map { AgendaTime(newId(), it.kind, it.label, fromTemplate = true) },
            ),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.new_event)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EventFields(fields, places) { fields = it }
                Text(stringResource(Res.string.new_event_lineup_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = fields.name.isNotBlank(), onClick = {
                onDismiss()
                onCreate(fields)
            }) {
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
