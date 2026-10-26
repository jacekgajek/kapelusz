package com.jacekgajek.kapelusz.app

import com.jacekgajek.kapelusz.app.generated.event_link_offline
import com.jacekgajek.kapelusz.app.data.LinkedEvent
import com.jacekgajek.kapelusz.app.data.linkedEvent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.event_link_opening
import com.jacekgajek.kapelusz.app.generated.event_link_no_access
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.ok
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.ui.EventsRoute
import com.jacekgajek.kapelusz.app.ui.HistoryRoute
import com.jacekgajek.kapelusz.app.ui.SummaryRoute
import com.jacekgajek.kapelusz.app.ui.LocalAppLocale
import com.jacekgajek.kapelusz.app.ui.NewBandRoute
import com.jacekgajek.kapelusz.app.ui.components.LoadingScreen
import com.jacekgajek.kapelusz.app.ui.components.LocalTopBarState
import com.jacekgajek.kapelusz.app.ui.components.SignInAgainDialog
import com.jacekgajek.kapelusz.app.ui.components.TopBarState
import com.jacekgajek.kapelusz.app.ui.model.isArtist
import com.jacekgajek.kapelusz.app.ui.screens.JoinDialog
import com.jacekgajek.kapelusz.app.ui.screens.SignInScreen
import com.jacekgajek.kapelusz.app.ui.screens.UnreadableDataScreen
import com.jacekgajek.kapelusz.app.ui.screens.UpdateRequiredScreen
import com.jacekgajek.kapelusz.app.ui.screens.ZeroScreen
import com.jacekgajek.kapelusz.app.ui.theme.KapeluszTheme
import kotlinx.coroutines.launch

@Composable
fun App(graph: AppGraph, onNavHostReady: suspend (NavController) -> Unit = {}) {
    // Members' Google photos load over Ktor on every platform, kept in memory and (Android) on disk.
    setSingletonImageLoaderFactory { context -> ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build() }
    val settings by graph.settings.settings.collectAsState()
    val bandState by graph.bands.state.collectAsState()
    val session by graph.sessions.session.collectAsState()
    // Outside key(language), so changing the language keeps the back stack. A new controller per signed-in account:
    // after signing out and in again the app starts on the event list instead of restoring the old back stack.
    val navController = key(session?.userId) { rememberNavController() }
    LaunchedEffect(navController) { onNavHostReady(navController) }
    CompositionLocalProvider(LocalAppLocale provides settings.language) {
        key(settings.language) {
            KapeluszTheme {
                val updateRequired by graph.updateRequired.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    if (updateRequired) {
                        UpdateRequiredScreen()
                    } else if (session == null) {
                        val signedOutEverywhere by graph.signedOutEverywhere.collectAsState()
                        SignInScreen(onIdToken = graph::onSignedIn, signedOutEverywhere = signedOutEverywhere)
                    } else {
                        BandContent(graph, bandState, navController)
                    }
                }
            }
        }
    }
}

/** Shown while the app loads its data, before [App]: a progress indicator, not a blank screen (ITERATION_3 R13). */
@Composable
fun AppLoading() {
    KapeluszTheme { Surface(Modifier.fillMaxSize()) { LoadingScreen() } }
}

@Composable
private fun BandContent(graph: AppGraph, bandState: BandState, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val index by graph.bands.index.collectAsState()
    val syncStatus by graph.sync.status.collectAsState()
    val stuck by graph.sync.stuck.collectAsState()
    val session by graph.sessions.session.collectAsState()
    val defaultName = session?.name?.substringBefore(' ').orEmpty()
    // Opening a band from the first screen must not navigate: the navigation host only exists once a band is open.
    // Switching from inside a band resets the back stack to the event list.
    var openError by remember { mutableStateOf(false) }
    // Any other band shown than the last one (also after having none, e.g. access lost, then opening, creating or
    // joining one) starts on its event list (V5-M1 C2b): the navigation host keeps its back stack meanwhile, and the
    // old band's screens (an event, a cost) would come back. Not on the app's first band: a web address must survive.
    val readyBandId = (bandState as? BandState.Ready)?.band?.id
    var shownBandId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(readyBandId, bandState is BandState.NoBand) {
        when {
            bandState is BandState.NoBand -> shownBandId = shownBandId?.let { NO_BAND }
            readyBandId != null && readyBandId != shownBandId -> {
                // Unless the switch already set up its screens (an event link opening one of its events).
                if (shownBandId != null && graph.navigatedForBand != readyBandId) navController.resetTo(EventsRoute)
                graph.navigatedForBand = null
                shownBandId = readyBandId
            }
        }
    }
    var signingInAgain by remember { mutableStateOf(false) }
    val openBand: (bandId: String, resetNavigation: Boolean) -> Unit = { bandId, resetNavigation ->
        scope.launch {
            openError = false
            val opened = if (graph.bands.hasStored(bandId)) {
                graph.bands.open(bandId)
                true
            } else {
                runSuspendCatching { graph.sync.download(bandId) }.isSuccess
            }
            if (!opened) openError = true
            if (opened && resetNavigation) navController.resetTo(EventsRoute)
            graph.sync.requestSync()
        }
    }
    val reauthenticate: suspend (String) -> Boolean = { token ->
        graph.sessions.reauthenticated(token).also { ok -> if (ok) graph.sync.resumeAfterSignIn() }
    }
    if (signingInAgain) SignInAgainDialog(session?.email, reauthenticate, onDismiss = { signingInAgain = false })
    // Joining: from a join link (pending code) or the "Join a band" buttons.
    val pendingJoinCode by graph.pendingJoinCode.collectAsState()
    var joining by remember { mutableStateOf(false) }
    if (joining || pendingJoinCode != null) {
        JoinDialog(
            graph = graph,
            initialCode = pendingJoinCode,
            onJoined = {
                joining = false
                graph.pendingJoinCode.value = null
                if (bandState is BandState.Ready) navController.resetTo(EventsRoute)
            },
            onDismiss = { joining = false; graph.pendingJoinCode.value = null },
        )
    }
    LostAccessDialog(graph)
    EventLinkOpening(graph)
    when (bandState) {
        BandState.Loading -> LoadingScreen()
        BandState.NoBand -> ZeroScreen(
            bands = index?.bands.orEmpty(),
            syncStatus = syncStatus,
            openError = openError,
            onSignInAgain = { signingInAgain = true },
            defaultYourName = defaultName,
            onOpen = { openBand(it, false) },
            onCreate = { name, currency, yourName -> graph.bands.createBand(name, currency, yourName); graph.sync.requestSync() },
            onSignOut = { graph.signOutInBackground() },
            onJoin = { joining = true },
        )
        is BandState.Ready -> CompositionLocalProvider(
            LocalTopBarState provides TopBarState(
                syncStatus = syncStatus,
                bands = index?.bands.orEmpty(),
                currentBandId = index?.currentBandId,
                stuckBandIds = stuck.keys,
                onSelectBand = { openBand(it, true) },
                onNewBand = { navController.navigate(NewBandRoute) },
                onJoinBand = { joining = true },
                onHistory = { navController.navigate(HistoryRoute) },
                onSummary = { navController.navigate(SummaryRoute) },
                onSyncNow = { graph.sync.requestSync() },
                onReauthenticate = reauthenticate,
                accountEmail = session?.email,
                artist = bandState.band.isArtist(session?.userId),
            ),
        ) { Navigation(graph, bandState, navController, defaultName) }
        is BandState.Unreadable -> UnreadableDataScreen(onStartOver = { scope.launch { graph.bands.startOver() } })
    }
}

/** [BandContent]'s marker: a band was shown, then none. */
private const val NO_BAND = "-"

/**
 * An event link (ITERATION_5 §10): opens its event in whichever of the user's bands has it, switching band if needed; "Opening the event…" while the bands are looked through (a new device downloads them first), and "no
 * access" when none of the user's bands has it for them.
 */
@Composable
private fun EventLinkOpening(graph: AppGraph) {
    val eventLink by graph.pendingEventLink.collectAsState()
    var failed by remember { mutableStateOf<LinkedEvent?>(null) }
    LaunchedEffect(eventLink) {
        val eventId = eventLink ?: return@LaunchedEffect
        when (val found = graph.linkedEvent(eventId)) {
            is LinkedEvent.Found -> {
                // Without an open band there are no screens yet to open it from: the band first, marked as opened for
                // the event, so that showing it doesn't reset the screens to its event list.
                if (graph.bands.state.value !is BandState.Ready) {
                    graph.navigatedForBand = found.bandId
                    graph.bands.open(found.bandId)
                }
                graph.pendingOpenEvent.value = found.bandId to eventId
            }
            else -> failed = found
        }
        // Last: clearing it ends this effect.
        graph.pendingEventLink.value = null
    }
    if (eventLink != null) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(Res.string.event_link_opening))
                }
            },
            confirmButton = { TextButton(onClick = { graph.pendingEventLink.value = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    failed?.let { reason ->
        AlertDialog(
            onDismissRequest = { failed = null },
            text = { Text(stringResource(if (reason == LinkedEvent.Unreachable) Res.string.event_link_offline else Res.string.event_link_no_access)) },
            confirmButton = { TextButton(onClick = { failed = null }) { Text(stringResource(Res.string.ok)) } },
        )
    }
}
