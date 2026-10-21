package com.jacekgajek.artsplit.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.BandState
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.tab_events
import com.jacekgajek.artsplit.app.generated.tab_my_costs
import com.jacekgajek.artsplit.app.generated.tab_settle
import com.jacekgajek.artsplit.app.ui.CostRoute
import com.jacekgajek.artsplit.app.ui.EventRoute
import com.jacekgajek.artsplit.app.ui.EventsRoute
import com.jacekgajek.artsplit.app.ui.LocalAppLocale
import com.jacekgajek.artsplit.app.ui.MyCostsRoute
import com.jacekgajek.artsplit.app.ui.RosterRoute
import com.jacekgajek.artsplit.app.ui.SettingsRoute
import com.jacekgajek.artsplit.app.ui.SettleRoute
import com.jacekgajek.artsplit.app.ui.screens.CostScreen
import com.jacekgajek.artsplit.app.ui.screens.CreateBandScreen
import com.jacekgajek.artsplit.app.ui.screens.EventsScreen
import com.jacekgajek.artsplit.app.ui.screens.MyCostsScreen
import com.jacekgajek.artsplit.app.ui.screens.RosterScreen
import com.jacekgajek.artsplit.app.ui.screens.SettingsScreen
import com.jacekgajek.artsplit.app.ui.screens.SettleScreen
import com.jacekgajek.artsplit.app.ui.screens.SignInScreen
import com.jacekgajek.artsplit.app.ui.screens.UnreadableDataScreen
import com.jacekgajek.artsplit.app.ui.screens.event.EventScreen
import com.jacekgajek.artsplit.app.ui.theme.ArtSplitTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class Tab(val route: Any, val icon: ImageVector, val label: StringResource) {
    EVENTS(EventsRoute, Icons.Default.Event, Res.string.tab_events),
    MY_COSTS(MyCostsRoute, Icons.AutoMirrored.Filled.ReceiptLong, Res.string.tab_my_costs),
    SETTLE(SettleRoute, Icons.Default.SwapHoriz, Res.string.tab_settle),
}

@Composable
fun App(graph: AppGraph, onNavHostReady: suspend (NavController) -> Unit = {}) {
    val settings by graph.settings.settings.collectAsState()
    val bandState by graph.bands.state.collectAsState()
    val session by graph.sessions.session.collectAsState()
    // Outside key(language), so changing the language keeps the back stack.
    val navController = rememberNavController()
    LaunchedEffect(navController) { onNavHostReady(navController) }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalAppLocale provides settings.language) {
        key(settings.language) {
            ArtSplitTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state = bandState
                    when {
                        state == BandState.Loading -> {}
                        session == null -> SignInScreen(graph.sessions)
                        else -> BandContent(graph, state, navController, scope)
                    }
                }
            }
        }
    }
}

@Composable
private fun BandContent(graph: AppGraph, bandState: BandState, navController: NavHostController, scope: CoroutineScope) {
    when (bandState) {
        BandState.Loading -> {}
        BandState.NoBand -> CreateBandScreen { name, currency, yourName -> graph.bands.createBand(name, currency, yourName) }
        is BandState.Ready -> Navigation(graph, bandState, navController)
        is BandState.Unreadable -> UnreadableDataScreen(onStartOver = { scope.launch { graph.bands.startOver() } })
    }
}

@Composable
private fun Navigation(graph: AppGraph, state: BandState.Ready, navController: NavHostController) {
    val settings by graph.settings.settings.collectAsState()
    val toSettings = { navController.navigate(SettingsRoute) }
    val toRoster = { navController.navigate(RosterRoute) }
    val back: () -> Unit = { navController.popBackStack() }

    MainScaffold(navController) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> {
                EventsScreen(graph, state, settings, { navController.navigate(EventRoute(it)) }, toSettings, toRoster)
            }
            composable<MyCostsRoute> { MyCostsScreen(state, { navController.navigate(CostRoute(it)) }, toSettings, toRoster) }
            composable<SettleRoute> { SettleScreen(graph, state, { navController.navigate(EventRoute(it)) }, toSettings, toRoster) }
            composable<EventRoute> { entry -> EventScreen(graph, state, entry.toRoute<EventRoute>().eventId, back) }
            composable<CostRoute> { entry -> CostScreen(graph, state, entry.toRoute<CostRoute>().costId, back) }
            composable<SettingsRoute> {
                val session by graph.sessions.session.collectAsState()
                val scope = rememberCoroutineScope()
                SettingsScreen(
                    language = settings.language,
                    onLanguage = { code -> graph.settings.update { it.copy(language = code) } },
                    account = session?.let { it.email ?: it.name },
                    onSignOut = { scope.launch { graph.sessions.signOut() } },
                    onBack = back,
                )
            }
            composable<RosterRoute> { RosterScreen(graph, state, back) }
        }
    }
}

/** Bottom navigation on phones, a rail on wide screens; shown only on the tab screens. */
@Composable
private fun MainScaffold(navController: NavHostController, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.route::class) == true }
    fun select(tab: Tab) = navController.navigate(tab.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        Scaffold(
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
