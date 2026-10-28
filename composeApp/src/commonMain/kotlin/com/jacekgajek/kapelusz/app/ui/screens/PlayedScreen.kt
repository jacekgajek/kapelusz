package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.clickable
import com.jacekgajek.kapelusz.app.generated.played_unplaced_hint
import com.jacekgajek.kapelusz.app.generated.played_unplaced
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.LocatedPlaces
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.back
import com.jacekgajek.kapelusz.app.generated.played_concerts
import com.jacekgajek.kapelusz.app.generated.played_empty
import com.jacekgajek.kapelusz.app.generated.played_failed
import com.jacekgajek.kapelusz.app.generated.played_finding
import com.jacekgajek.kapelusz.app.generated.played_missing
import com.jacekgajek.kapelusz.app.generated.played_missing_hint
import com.jacekgajek.kapelusz.app.generated.played_show_on_map
import com.jacekgajek.kapelusz.app.generated.played_title
import com.jacekgajek.kapelusz.app.platform.PlacesMap
import com.jacekgajek.kapelusz.app.platform.mapPickerAvailable
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.summaryPeriod
import com.jacekgajek.kapelusz.domain.PlayedPlaces
import com.jacekgajek.kapelusz.model.Event
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Where we played" (ITERATION_7 §2f): the concerts of the Summary's period on a map, one marker per place; a list on
 * the web, which has no map. Below, what is still being found and what Google can't find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayedScreen(graph: AppGraph, state: BandState.Ready, onBack: () -> Unit, onOpenEvent: (String) -> Unit) {
    val band = state.band
    val today = today()
    val (from, to) = graph.settings.settings.collectAsState().value.summaryPeriod(band.id).range(today)
    val events = remember(band, state.me, from, to, today) { PlayedPlaces.played(band, state.me, from, to, today) }
    // Null until the coordinates this device already knows are read (a moment): the map opens on those, not on Poland.
    val first by produceState<LocatedPlaces?>(null, band.id) { graph.places.locate(band.id).collect { value = it } }
    val located = first ?: LocatedPlaces(pending = -1)
    val (places, missing) = remember(events, located) { PlayedPlaces.places(events) { located.coordinates[PlayedPlaces.key(it)] } }
    // Not found: Google said so. The rest without coordinates: still being looked up, or not placed (offline, not sent yet).
    val (notFound, unplaced) = missing.partition { event -> event.location?.let { PlayedPlaces.key(it) in located.notFound } == true }
    val finding = located.finding && unplaced.isNotEmpty()
    var opened by remember { mutableStateOf<PlayedPlaces.Place?>(null) }
    val openPlace = { place: PlayedPlaces.Place -> opened = place }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.played_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back)) } },
            )
        },
    ) { padding ->
        if (events.isEmpty()) {
            Text(stringResource(Res.string.played_empty), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        if (mapPickerAvailable) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                // The panel only when it has something to say; the map keeps its controls and the fit clear of it.
                val hasStatus = finding || located.failed || notFound.isNotEmpty() || unplaced.isNotEmpty()
                var panel by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                if (first != null) PlacesMap(places, openPlace, Modifier.fillMaxSize(), bottomInset = with(density) { (if (hasStatus) panel else 0).toDp() })
                if (hasStatus) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { panel = it.height }, tonalElevation = 3.dp) {
                    // At most a third of the map: the list of missing concerts scrolls.
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        PlacesStatus(located, finding, notFound, unplaced, onOpenEvent)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).listWidth(), contentPadding = PaddingValues(16.dp)) {
                item { PlacesStatus(located, finding, notFound, unplaced, onOpenEvent) }
                items(places, key = { "${it.latitude},${it.longitude}" }) { place ->
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { PlaceDetails(place, onOpenEvent) }
                }
            }
        }
    }
    opened?.let { place ->
        ModalBottomSheet(onDismissRequest = { opened = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) { PlaceDetails(place, onOpenEvent) }
        }
    }
}

/**
 * "Finding places…", or that the server couldn't be asked; the concerts Google can't place (to fix), and those not placed
 * yet once nothing is being found: every concert shows somewhere.
 */
@Composable
private fun PlacesStatus(located: LocatedPlaces, finding: Boolean, notFound: List<Event>, unplaced: List<Event>, onOpenEvent: (String) -> Unit) {
    if (finding) {
        Text(stringResource(Res.string.played_finding), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
    if (located.failed) Text(stringResource(Res.string.played_failed), style = MaterialTheme.typography.bodySmall)
    MissingConcerts(Res.plurals.played_missing, Res.string.played_missing_hint, notFound, onOpenEvent)
    if (!finding) MissingConcerts(Res.plurals.played_unplaced, Res.string.played_unplaced_hint, unplaced, onOpenEvent)
}

/** A folded list of concerts not on the map, each opening its event. */
@Composable
private fun MissingConcerts(title: PluralStringResource, hint: StringResource, events: List<Event>, onOpenEvent: (String) -> Unit) {
    if (events.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) {
            Text(pluralStringResource(title, events.size, events.size), Modifier.padding(end = 4.dp))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (open) {
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            events.forEach { event -> ConcertRow(event, event.location, onOpenEvent) }
        }
    }
}

/** A place: its full address, how to open it in Google Maps, its concerts newest first. */
@Composable
private fun PlaceDetails(place: PlayedPlaces.Place, onOpenEvent: (String) -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.padding(16.dp)) {
        Text(place.title, style = MaterialTheme.typography.titleMedium)
        Text(place.address, style = MaterialTheme.typography.bodySmall)
        Text(pluralStringResource(Res.plurals.played_concerts, place.events.size, place.events.size), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge)
        if (!mapPickerAvailable) TextButton(onClick = { uri.openUri("https://www.google.com/maps/search/?api=1&query=${place.latitude},${place.longitude}") }) {
            Text(stringResource(Res.string.played_show_on_map))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        place.events.forEach { ConcertRow(it, null, onOpenEvent) }
    }
}

@Composable
private fun ConcertRow(event: Event, detail: String?, onOpenEvent: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpenEvent(event.id) }.padding(vertical = 6.dp)) {
        Text(event.name)
        Text(listOfNotNull(formatDate(event.date, language), detail).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}
