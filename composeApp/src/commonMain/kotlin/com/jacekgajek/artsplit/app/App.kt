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
import com.jacekgajek.artsplit.app.ui.screens.CreateBandScreen
import com.jacekgajek.artsplit.app.ui.screens.EventsScreen
import com.jacekgajek.artsplit.app.ui.screens.PlaceholderScreen
import com.jacekgajek.artsplit.app.ui.screens.RosterScreen
import com.jacekgajek.artsplit.app.ui.screens.SettingsScreen
import com.jacekgajek.artsplit.app.ui.theme.ArtSplitTheme
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
    CompositionLocalProvider(LocalAppLocale provides settings.language) {
        key(settings.language) {
            ArtSplitTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (val state = bandState) {
                        BandState.Loading -> {}
                        BandState.NoBand -> CreateBandScreen { name, currency, yourName ->
                            graph.bands.createBand(name, currency, yourName)
                        }
                        is BandState.Ready -> Navigation(graph, state, onNavHostReady)
                    }
                }
            }
        }
    }
}

@Composable
private fun Navigation(graph: AppGraph, state: BandState.Ready, onNavHostReady: suspend (NavController) -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    val navController = rememberNavController()
    LaunchedEffect(navController) { onNavHostReady(navController) }
    val toSettings = { navController.navigate(SettingsRoute) }
    val toRoster = { navController.navigate(RosterRoute) }
    val back: () -> Unit = { navController.popBackStack() }

    MainScaffold(navController) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> {
                EventsScreen(graph, state, settings, { navController.navigate(EventRoute(it)) }, toSettings, toRoster)
            }
            composable<MyCostsRoute> { PlaceholderScreen(stringResource(Res.string.tab_my_costs), toSettings, toRoster) }
            composable<SettleRoute> { PlaceholderScreen(stringResource(Res.string.tab_settle), toSettings, toRoster) }
            composable<EventRoute> { entry ->
                val route = entry.toRoute<EventRoute>()
                PlaceholderScreen(state.band.event(route.eventId)?.name ?: "", toSettings, toRoster)
            }
            composable<CostRoute> { PlaceholderScreen("", toSettings, toRoster) }
            composable<SettingsRoute> {
                SettingsScreen(
                    language = settings.language,
                    onLanguage = { code -> graph.settings.update { it.copy(language = code) } },
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
