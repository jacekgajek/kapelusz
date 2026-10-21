package com.jacekgajek.artsplit.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.artsplit.app.generated.lost_access
import com.jacekgajek.artsplit.app.generated.ok
import com.jacekgajek.artsplit.app.generated.sync_refused
import com.jacekgajek.artsplit.app.sync.SyncStatus
import com.jacekgajek.artsplit.app.ui.HistoryRoute
import com.jacekgajek.artsplit.app.ui.NewBandRoute
import com.jacekgajek.artsplit.app.ui.components.LocalTopBarState
import com.jacekgajek.artsplit.app.ui.components.SignInAgainDialog
import com.jacekgajek.artsplit.app.ui.components.TopBarState
import com.jacekgajek.artsplit.app.ui.components.labelRes
import com.jacekgajek.artsplit.app.ui.screens.NewBandScreen
import com.jacekgajek.artsplit.app.ui.screens.ZeroScreen
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.SetTreasurer
import com.jacekgajek.artsplit.app.ui.model.Can
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.tab_events
import com.jacekgajek.artsplit.app.generated.tab_my_costs
import com.jacekgajek.artsplit.app.generated.tab_settle
import com.jacekgajek.artsplit.app.generated.tab_fund
import com.jacekgajek.artsplit.app.ui.CostRoute
import com.jacekgajek.artsplit.app.ui.EventRoute
import com.jacekgajek.artsplit.app.ui.EventsRoute
import com.jacekgajek.artsplit.app.ui.LocalAppLocale
import com.jacekgajek.artsplit.app.ui.MyCostsRoute
import com.jacekgajek.artsplit.app.ui.RosterRoute
import com.jacekgajek.artsplit.app.ui.SettingsRoute
import com.jacekgajek.artsplit.app.ui.SettleRoute
import com.jacekgajek.artsplit.app.ui.FundRoute
import com.jacekgajek.artsplit.app.ui.screens.BandFundScreen
import com.jacekgajek.artsplit.app.ui.screens.CostScreen
import com.jacekgajek.artsplit.app.ui.screens.EventsScreen
import com.jacekgajek.artsplit.app.ui.screens.HistoryScreen
import com.jacekgajek.artsplit.app.ui.screens.JoinDialog
import com.jacekgajek.artsplit.app.ui.screens.MyCostsScreen
import com.jacekgajek.artsplit.app.ui.screens.RosterScreen
import com.jacekgajek.artsplit.app.ui.screens.SettingsScreen
import com.jacekgajek.artsplit.app.ui.screens.SettleScreen
import com.jacekgajek.artsplit.app.ui.screens.SignInScreen
import com.jacekgajek.artsplit.app.ui.screens.UnreadableDataScreen
import com.jacekgajek.artsplit.app.ui.screens.event.EventScreen
import com.jacekgajek.artsplit.app.ui.theme.ArtSplitTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class Tab(val route: Any, val icon: ImageVector, val label: StringResource) {
    EVENTS(EventsRoute, Icons.Default.Event, Res.string.tab_events),
    MY_COSTS(MyCostsRoute, Icons.AutoMirrored.Filled.ReceiptLong, Res.string.tab_my_costs),
    SETTLE(SettleRoute, Icons.Default.SwapHoriz, Res.string.tab_settle),
    FUND(FundRoute, Icons.Default.Savings, Res.string.tab_fund),
}

@Composable
fun App(graph: AppGraph, onNavHostReady: suspend (NavController) -> Unit = {}) {
    val settings by graph.settings.settings.collectAsState()
    val bandState by graph.bands.state.collectAsState()
    val session by graph.sessions.session.collectAsState()
    // Outside key(language), so changing the language keeps the back stack. A new controller per signed-in account:
    // after signing out and in again the app starts on the event list instead of restoring the old back stack.
    val navController = key(session?.userId) { rememberNavController() }
    LaunchedEffect(navController) { onNavHostReady(navController) }
    CompositionLocalProvider(LocalAppLocale provides settings.language) {
        key(settings.language) {
            ArtSplitTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (session == null) {
                        SignInScreen(onIdToken = graph::onSignedIn)
                    } else {
                        BandContent(graph, bandState, navController)
                    }
                }
            }
        }
    }
}

@Composable
private fun BandContent(graph: AppGraph, bandState: BandState, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val index by graph.bands.index.collectAsState()
    val syncStatus by graph.sync.status.collectAsState()
    val session by graph.sessions.session.collectAsState()
    val defaultName = session?.name?.substringBefore(' ').orEmpty()
    // Opening a band from the first screen must not navigate: the navigation host only exists once a band is open,
    // and it starts on the event list anyway. Switching from inside a band resets the back stack to the event list.
    var openError by remember { mutableStateOf(false) }
    var signingInAgain by remember { mutableStateOf(false) }
    val openBand: (bandId: String, resetNavigation: Boolean) -> Unit = { bandId, resetNavigation ->
        scope.launch {
            openError = false
            val opened = if (graph.bands.hasStored(bandId)) {
                graph.bands.open(bandId)
                true
            } else {
                runCatching { graph.sync.download(bandId) }.isSuccess
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
    // A dialog, not a snackbar: losing the open band leaves its screens, so they cannot show the message.
    var lostBand by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph) { graph.sync.lostAccess.collect { lostBand = it } }
    lostBand?.let { name ->
        AlertDialog(
            onDismissRequest = { lostBand = null },
            text = { Text(stringResource(Res.string.lost_access, name)) },
            confirmButton = { TextButton(onClick = { lostBand = null }) { Text(stringResource(Res.string.ok)) } },
        )
    }
    when (bandState) {
        BandState.Loading -> {}
        BandState.NoBand -> ZeroScreen(
            bands = index?.bands.orEmpty(),
            syncStatus = syncStatus,
            openError = openError,
            onSignInAgain = { signingInAgain = true },
            defaultYourName = defaultName,
            onOpen = { openBand(it, false) },
            onCreate = { name, currency, yourName -> graph.bands.createBand(name, currency, yourName); graph.sync.requestSync() },
            onSignOut = { scope.launch { graph.signOut() } },
            onJoin = { joining = true },
        )
        is BandState.Ready -> CompositionLocalProvider(
            LocalTopBarState provides TopBarState(
                syncStatus = syncStatus,
                bands = index?.bands.orEmpty(),
                currentBandId = index?.currentBandId,
                onSelectBand = { openBand(it, true) },
                onNewBand = { navController.navigate(NewBandRoute) },
                onJoinBand = { joining = true },
                onHistory = { navController.navigate(HistoryRoute) },
                onSyncNow = { graph.sync.requestSync() },
                onReauthenticate = reauthenticate,
                accountEmail = session?.email,
            ),
        ) { Navigation(graph, bandState, navController, defaultName) }
        is BandState.Unreadable -> UnreadableDataScreen(onStartOver = { scope.launch { graph.bands.startOver() } })
    }
}

@Composable
private fun Navigation(graph: AppGraph, state: BandState.Ready, navController: NavHostController, defaultName: String) {
    val settings by graph.settings.settings.collectAsState()
    // After signing in, start from the event list: on the web the URL would otherwise restore the last screen
    // (e.g. Settings, where the user signed out).
    val signIns by graph.signIns.collectAsState()
    LaunchedEffect(signIns) { if (signIns > 0) navController.resetTo(EventsRoute) }
    val toSettings = { navController.navigate(SettingsRoute) }
    val toRoster = { navController.navigate(RosterRoute) }
    val back: () -> Unit = { navController.popBackStack() }

    MainScaffold(navController, graph) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> {
                EventsScreen(graph, state, settings, { navController.navigate(EventRoute(it)) }, toSettings, toRoster)
            }
            composable<MyCostsRoute> { MyCostsScreen(state, { navController.navigate(CostRoute(it)) }, toSettings, toRoster) }
            composable<SettleRoute> { SettleScreen(graph, state, { navController.navigate(EventRoute(it)) }, toSettings, toRoster) }
            composable<FundRoute> { BandFundScreen(graph, state, toSettings, toRoster, { navController.navigate(EventRoute(it)) }) }
            composable<EventRoute> { entry -> EventScreen(graph, state, entry.toRoute<EventRoute>().eventId, back) }
            composable<CostRoute> { entry -> CostScreen(graph, state, entry.toRoute<CostRoute>().costId, back) }
            composable<SettingsRoute> {
                val session by graph.sessions.session.collectAsState()
                val scope = rememberCoroutineScope()
                SettingsScreen(
                    language = settings.language,
                    onLanguage = { code -> graph.settings.update { it.copy(language = code) } },
                    account = session?.let { it.email ?: it.name },
                    onSignOut = { scope.launch { graph.signOut() } },
                    bandName = state.band.name,
                    onRenameBand = { name: String -> graph.dispatch(RenameBand(name)); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(RenameBand("probe")) },
                    treasurer = state.band.treasurerMemberId,
                    treasurerOptions = state.band.members.filter { !it.archived }.map { it.id to it.name },
                    onTreasurer = { id: String -> graph.dispatch(SetTreasurer(id)); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(SetTreasurer("probe")) },
                    onBack = back,
                )
            }
            composable<RosterRoute> { RosterScreen(graph, state, back) }
            composable<HistoryRoute> { HistoryScreen(graph, state, back) }
            composable<NewBandRoute> {
                NewBandScreen(defaultName, onCreate = { name, currency, yourName ->
                    graph.bands.createBand(name, currency, yourName)
                    graph.sync.requestSync()
                    navController.resetTo(EventsRoute)
                }, onBack = back)
            }
        }
    }
}

/** Navigates to [route] and drops everything else from the back stack. */
private fun NavHostController.resetTo(route: Any) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}

/** Bottom navigation on phones, a rail on wide screens; shown only on the tab screens. */
@Composable
private fun MainScaffold(navController: NavHostController, graph: AppGraph, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    // Changes the server refused during sync (e.g. a role changed meanwhile).
    val snackbar = remember { SnackbarHostState() }
    val reasonTexts = RejectionReason.entries.associateWith { stringResource(it.labelRes()) }
    val refusedPrefix = stringResource(Res.string.sync_refused)
    LaunchedEffect(graph) {
        graph.sync.rejections.collect { snackbar.showSnackbar("$refusedPrefix ${reasonTexts.getValue(it.reason)}") }
    }
    val current = Tab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.route::class) == true }
    fun select(tab: Tab) = navController.navigate(tab.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide && current != null) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == current,
                                onClick = { select(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding)) {
                if (wide && current != null) {
                    NavigationRail {
                        Tab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = tab == current,
                                onClick = { select(tab) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
                content()
            }
        }
    }
}
