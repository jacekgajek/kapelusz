package com.jacekgajek.kapelusz.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.bands.Membership
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.tab_events
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import com.jacekgajek.kapelusz.app.generated.tab_settle
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.ui.AppNavigator
import com.jacekgajek.kapelusz.app.ui.CostRoute
import com.jacekgajek.kapelusz.app.ui.EventRoute
import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.app.ui.EventsRoute
import com.jacekgajek.kapelusz.app.ui.FundRoute
import com.jacekgajek.kapelusz.app.ui.HistoryRoute
import com.jacekgajek.kapelusz.app.ui.MyCostsRoute
import com.jacekgajek.kapelusz.app.ui.NewBandRoute
import com.jacekgajek.kapelusz.app.ui.PlayedRoute
import com.jacekgajek.kapelusz.app.ui.RosterRoute
import com.jacekgajek.kapelusz.app.ui.SettingsRoute
import com.jacekgajek.kapelusz.app.ui.SettleRoute
import com.jacekgajek.kapelusz.app.ui.SummaryRoute
import com.jacekgajek.kapelusz.app.ui.components.AppBanner
import com.jacekgajek.kapelusz.app.ui.components.MessageHost
import com.jacekgajek.kapelusz.app.ui.components.StuckPushBanner
import com.jacekgajek.kapelusz.app.ui.components.UiMessage
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.rememberBandSession
import com.jacekgajek.kapelusz.app.ui.screenViewModel
import com.jacekgajek.kapelusz.app.ui.screens.BandFundScreen
import com.jacekgajek.kapelusz.app.ui.screens.CostScreen
import com.jacekgajek.kapelusz.app.ui.screens.EventsScreen
import com.jacekgajek.kapelusz.app.ui.screens.EventsViewModel
import com.jacekgajek.kapelusz.app.ui.screens.FundEntryViewModel
import com.jacekgajek.kapelusz.app.ui.screens.HistoryScreen
import com.jacekgajek.kapelusz.app.ui.screens.HistoryViewModel
import com.jacekgajek.kapelusz.app.ui.screens.MyCostsScreen
import com.jacekgajek.kapelusz.app.ui.screens.NewBandScreen
import com.jacekgajek.kapelusz.app.ui.screens.PlayedScreen
import com.jacekgajek.kapelusz.app.ui.screens.RosterScreen
import com.jacekgajek.kapelusz.app.ui.screens.RosterViewModel
import com.jacekgajek.kapelusz.app.ui.screens.SettleScreen
import com.jacekgajek.kapelusz.app.ui.screens.SummaryScreen
import com.jacekgajek.kapelusz.app.ui.screens.costViewModel
import com.jacekgajek.kapelusz.app.ui.screens.event.EventScreen
import com.jacekgajek.kapelusz.app.ui.screens.event.EventViewModel
import com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsPage
import com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsScreen
import com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsViewModel
import com.jacekgajek.kapelusz.app.ui.screens.settings.settingsFor
import com.jacekgajek.kapelusz.rules.isArtist
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A screen an artist does not get (e.g. a bookmarked web address, or a role changed while on it): the event list. */
@Composable
private fun BackToEvents(navController: NavHostController) {
    LaunchedEffect(Unit) { navController.resetTo(EventsRoute) }
}

private enum class Tab(val route: Any, val icon: ImageVector, val label: StringResource) {
    EVENTS(EventsRoute, Icons.Default.Event, Res.string.tab_events),
    MY_COSTS(MyCostsRoute, Icons.AutoMirrored.Filled.ReceiptLong, Res.string.tab_my_costs),
    SETTLE(SettleRoute, Icons.Default.SwapHoriz, Res.string.tab_settle),
    FUND(FundRoute, Icons.Default.Savings, Res.string.tab_fund),
}

@Composable
internal fun Navigation(runtime: AppRuntime, state: BandState.Ready, navController: NavHostController, defaultName: String) {
    val settings by runtime.settings.settings.collectAsState()
    LaunchedEffect(state.me?.pictureUrl) { runtime.account.sendPictureIfMissing(state.me) }
    val session = rememberBandSession(state, runtime)
    val navigator = remember(navController, runtime) { AppNavigator(navController) }

    // The band fund tab appears once someone holds the fund (leaders choose the holder in Settings).
    val fundEnabled = state.band.treasurerMemberId != null
    // Artists get their events and their costs only (ITERATION_4 §1.1).
    val artist = state.band.isArtist(state.userId)
    val tabs = Tab.entries.filter { (it != Tab.FUND || fundEnabled) && (!artist || it == Tab.EVENTS || it == Tab.MY_COSTS) }
    // E.g. after switching to a band without a band fund while on its tab.
    LaunchedEffect(fundEnabled) {
        if (!fundEnabled && navController.currentDestination?.hasRoute(FundRoute::class) == true) navController.resetTo(EventsRoute)
    }
    MainScaffold(navController, runtime.sync, runtime.bands, runtime.membership, state.band.id, tabs) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> { entry ->
                EventsScreen(session, settings, navigator, events = entry.screenViewModel { EventsViewModel(session.bands.state) })
            }
            composable<MyCostsRoute> { entry ->
                val everyone by entry.savedStateHandle.getStateFlow(AppNavigator.SHOW_EVERYONE, false).collectAsState()
                MyCostsScreen(session, everyone, { entry.savedStateHandle[AppNavigator.SHOW_EVERYONE] = it }, navigator)
            }
            composable<SettleRoute> {
                if (artist) BackToEvents(navController) else SettleScreen(session, navigator)
            }
            composable<FundRoute> { entry ->
                val addedCost by entry.savedStateHandle.getStateFlow<String?>(AppNavigator.ADDED_COST, null).collectAsState()
                if (artist) {
                    BackToEvents(navController)
                } else {
                    BandFundScreen(
                        session,
                        navigator,
                        entries = entry.screenViewModel { FundEntryViewModel() },
                        addedCost = addedCost,
                        onAddedCostSeen = { entry.savedStateHandle[AppNavigator.ADDED_COST] = null },
                    )
                }
            }
            composable<EventRoute> { entry ->
                val route = entry.toRoute<EventRoute>()
                // The section to show once (not again after a rotation): the route's, cleared once shown.
                val asked by entry.savedStateHandle.getStateFlow(AppNavigator.EVENT_SECTION, route.section?.name).collectAsState()
                // What travels with it (files shared to the event, ITERATION_6 §1): taken once, when the screen's model is made.
                val screen = entry.screenViewModel {
                    val carry = route.carryId?.let(session.intents::takeCarry)
                    EventViewModel(route.eventId, session.bands.state, carry)
                }
                EventScreen(session, navigator, screen, section = asked?.let(EventSection::valueOf)) {
                    entry.savedStateHandle[AppNavigator.EVENT_SECTION] = null
                }
            }
            composable<CostRoute> { entry ->
                val route = entry.toRoute<CostRoute>()
                // Artists open only their own costs (e.g. not from a web address someone shared).
                if (artist && route.costId != null && state.band.cost(route.costId)?.memberId.let { it != null && it != state.me?.id }) {
                    BackToEvents(navController)
                    return@composable
                }
                CostScreen(session, navigator, form = entry.screenViewModel(costViewModel(session, route, language)))
            }
            composable<SettingsRoute> { entry ->
                val page = entry.toRoute<SettingsRoute>().page?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
                val screen = entry.screenViewModel { SettingsViewModel { session.changes.dispatch(it) } }
                SettingsScreen(
                    settingsFor(session, onBandGone = navigator::bandGone, dispatch = screen::dispatch),
                    screen,
                    page,
                    // Only from the list while it is on screen, so a double tap does not open the page twice.
                    onOpenPage = { if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(SettingsRoute(it.name)) },
                    onOpenMembers = navigator::members,
                    onBack = navigator::back,
                )
            }
            composable<RosterRoute> { entry ->
                val roster = entry.screenViewModel {
                    val invites = session.invitesApi
                    val bandId = session.band.id
                    RosterViewModel(
                        invitesOf = { invites.invites(bandId).invites },
                        createInvite = { memberId, role -> invites.createInvite(bandId, memberId, role) },
                        revokeInvite = { invites.revokeInvite(bandId, it) },
                    )
                }
                RosterScreen(session, navigator, roster, openMember = entry.toRoute<RosterRoute>().memberId)
            }
            composable<SummaryRoute> { SummaryScreen(session, navigator) }
            composable<PlayedRoute> { PlayedScreen(session, navigator) }
            composable<HistoryRoute> { entry ->
                if (artist) {
                    BackToEvents(navController)
                } else {
                    val screen = entry.screenViewModel {
                        val history = session.historyApi
                        val bandId = session.band.id
                        HistoryViewModel(session.bands, session::syncNow, bandId, session.userId, session.me?.id) { month, next ->
                            history.history(bandId, month, next)
                        }
                    }
                    HistoryScreen(session, navigator, screen)
                }
            }
            composable<NewBandRoute> {
                NewBandScreen(defaultName, onCreate = { name, currency, yourName ->
                    runtime.membership.createBand(name, currency, yourName)
                    navController.resetTo(EventsRoute)
                }, onBack = navigator::back)
            }
        }
    }
}

/** Bottom navigation on phones, a rail on wide screens; shown only on the tab screens. */
@Composable
private fun MainScaffold(navController: NavHostController, sync: SyncEngine, bands: BandRepository, membership: Membership, bandId: String, tabs: List<Tab>, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    // Changes the server refused during sync (e.g. a role changed meanwhile).
    val messages = rememberMessages()
    LaunchedEffect(sync) {
        sync.rejections.collect { messages.post(UiMessage.SyncRefused(it.reason)) }
    }
    val current = Tab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.route::class) == true }
    // Changes the server refuses as a whole (ITERATION_3 R9).
    val stuck = sync.stuck.collectAsState().value[bandId]
    fun select(tab: Tab) = navController.navigate(tab.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        Scaffold(
            snackbarHost = { MessageHost(messages) },
            bottomBar = {
                Column {
                    if (current != null && stuck != null) {
                        StuckPushBanner(
                            stuck,
                            { bands.pendingAsJson(bandId) },
                            { membership.discardStuck(bandId) },
                            { messages.snackbar.showSnackbar(it) },
                        )
                    }
                    if (current != null) AppBanner(eventList = current == Tab.EVENTS)
                    if (!wide && current != null) {
                        NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = tab == current,
                                    onClick = { select(tab) },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = { Text(stringResource(tab.label)) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding)) {
                if (wide && current != null) {
                    NavigationRail {
                        tabs.forEach { tab ->
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

/** Navigates to [route] and drops everything else from the back stack. */
internal fun NavHostController.resetTo(route: Any) {
    // The tabs' saved back stacks too (V5-M1 C2a): after a band switch, a tab must not bring back an event or a cost
    // of the band before.
    Tab.entries.forEach { clearBackStack(it.route) }
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}
