package com.jacekgajek.kapelusz.app

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
import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.AppState
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.intents.AppIntent
import com.jacekgajek.kapelusz.app.intents.BandNavigator
import com.jacekgajek.kapelusz.app.intents.IntentRouter
import com.jacekgajek.kapelusz.app.ui.HistoryRoute
import com.jacekgajek.kapelusz.app.ui.LocalAppLocale
import com.jacekgajek.kapelusz.app.ui.NewBandRoute
import com.jacekgajek.kapelusz.app.ui.RosterRoute
import com.jacekgajek.kapelusz.app.ui.SettingsRoute
import com.jacekgajek.kapelusz.app.ui.SummaryRoute
import com.jacekgajek.kapelusz.app.ui.components.LoadingScreen
import com.jacekgajek.kapelusz.app.ui.components.LocalCurrency
import com.jacekgajek.kapelusz.app.ui.components.LocalTopBarState
import com.jacekgajek.kapelusz.app.ui.components.SignInAgainDialog
import com.jacekgajek.kapelusz.app.ui.components.TopBarState
import com.jacekgajek.kapelusz.app.ui.screens.SignInScreen
import com.jacekgajek.kapelusz.app.ui.screens.UnreadableDataScreen
import com.jacekgajek.kapelusz.app.ui.screens.UpdateRequiredScreen
import com.jacekgajek.kapelusz.app.ui.screens.ZeroScreen
import com.jacekgajek.kapelusz.app.ui.theme.KapeluszTheme
import com.jacekgajek.kapelusz.rules.isArtist
import kotlinx.coroutines.launch

@Composable
fun App(graph: AppGraph, onNavHostReady: suspend (NavController) -> Unit = {}) {
    // Members' Google photos load over Ktor on every platform, kept in memory and (Android) on disk.
    setSingletonImageLoaderFactory { context -> ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build() }
    val settings by graph.settings.settings.collectAsState()
    val state by graph.state.collectAsState(graph.currentState)
    // Outside key(language), so changing the language keeps the back stack. A new controller per signed-in account:
    // after signing out and in again the app starts on the event list instead of restoring the old back stack.
    val navController = key((state as? AppState.SignedIn)?.session?.userId) { rememberNavController() }
    LaunchedEffect(navController) { onNavHostReady(navController) }
    CompositionLocalProvider(LocalAppLocale provides settings.language) {
        key(settings.language) {
            KapeluszTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (val shown = state) {
                        AppState.UpdateRequired -> UpdateRequiredScreen()
                        is AppState.SignedOut -> SignInScreen(onIdToken = graph.account::onSignedIn, signedOutEverywhere = shown.everywhere)
                        is AppState.SignedIn -> BandContent(graph, shown.session, shown.band, navController)
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
private fun BandContent(graph: AppGraph, session: Session, bandState: BandState, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val index by graph.bands.index.collectAsState()
    val syncStatus by graph.sync.status.collectAsState()
    val stuck by graph.sync.stuck.collectAsState()
    val defaultName = session.name?.substringBefore(' ').orEmpty()
    var openError by remember { mutableStateOf(false) }
    // Whose screens are up: the navigator starts another band's over (see it); saved, as recreation keeps the back stack.
    val shownBandId = rememberSaveable { mutableStateOf<String?>(null) }
    val navigator = remember(navController, graph) { BandNavigator(navController, graph.bands, graph.sync, shownBandId) }
    val readyBandId = (bandState as? BandState.Ready)?.band?.id
    LaunchedEffect(readyBandId, bandState is BandState.NoBand) { navigator.bandShown(readyBandId, bandState is BandState.NoBand) }
    var signingInAgain by remember { mutableStateOf(false) }
    // Opening a band from the first screen must not navigate: the navigation host only exists once a band is open.
    // Switching from inside a band resets the back stack to the event list.
    val openBand: (bandId: String, resetNavigation: Boolean) -> Unit = { bandId, resetNavigation ->
        scope.launch {
            val opened = navigator.openBand(bandId, forScreens = false)
            openError = !opened
            if (opened && resetNavigation) navigator.eventList()
            graph.sync.requestSync()
        }
    }
    val reauthenticate: suspend (String) -> Boolean = { token ->
        graph.sessions.reauthenticated(token).also { ok -> if (ok) graph.sync.resumeAfterSignIn() }
    }
    if (signingInAgain) SignInAgainDialog(session.email, reauthenticate, onDismiss = { signingInAgain = false })
    LostAccessDialog(graph.sync)
    IntentRouter(graph, bandState, navigator)
    // The "Join a band" buttons: the router's dialog, like a join link's.
    val join = { graph.intents.offer(AppIntent.Join()) }
    when (bandState) {
        BandState.Loading -> LoadingScreen()

        BandState.NoBand -> ZeroScreen(
            bands = index?.bands.orEmpty(),
            syncStatus = syncStatus,
            openError = openError,
            onSignInAgain = { signingInAgain = true },
            defaultYourName = defaultName,
            onOpen = { openBand(it, false) },
            onCreate = { name, currency, yourName ->
                graph.bands.createBand(name, currency, yourName)
                graph.sync.requestSync()
            },
            onSignOut = { graph.account.signOutInBackground() },
            onJoin = join,
        )

        is BandState.Ready -> CompositionLocalProvider(
            LocalCurrency provides bandState.band.currency,
            LocalTopBarState provides TopBarState(
                syncStatus = syncStatus,
                bands = index?.bands.orEmpty(),
                currentBandId = index?.currentBandId,
                stuckBandIds = stuck.keys,
                onSelectBand = { openBand(it, true) },
                onNewBand = { navController.navigate(NewBandRoute) },
                onJoinBand = join,
                onHistory = { navController.navigate(HistoryRoute) },
                onSettings = { navController.navigate(SettingsRoute()) },
                onRoster = { navController.navigate(RosterRoute()) },
                onSummary = { navController.navigate(SummaryRoute) },
                onSyncNow = { graph.sync.syncAsked() },
                onReauthenticate = reauthenticate,
                accountEmail = session.email,
                artist = bandState.band.isArtist(session.userId),
            ),
        ) { Navigation(graph, bandState, navController, defaultName) }

        is BandState.Unreadable -> UnreadableDataScreen(onStartOver = { scope.launch { graph.bands.startOver() } })
    }
}
