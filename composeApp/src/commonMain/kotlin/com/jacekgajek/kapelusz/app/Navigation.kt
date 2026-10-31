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
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.Membership
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.tab_events
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import com.jacekgajek.kapelusz.app.generated.tab_settle
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
import com.jacekgajek.kapelusz.app.ui.screens.FundEntryViewModel
import com.jacekgajek.kapelusz.app.ui.screens.HistoryScreen
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
internal fun Navigation(graph: AppGraph, state: BandState.Ready, navController: NavHostController, defaultName: String) {
    val settings by graph.settings.settings.collectAsState()
    // After signing in, start from the event list: on the web the URL would otherwise restore the last screen
    // (e.g. Settings, where the user signed out).
    val signIns by graph.account.signIns.collectAsState()
    // Each sign-in once: this screen is composed again on a language change or (Android) rotation, which must keep
    // where the user is.
    LaunchedEffect(signIns) {
        if (signIns > graph.account.handledSignIns) {
            graph.account.handledSignIns = signIns
            navController.resetTo(EventsRoute)
        }
    }
    // An event to open (e.g. an event link): open it, switching band if needed.
    val openEvent by graph.incoming.pendingOpenEvent.collectAsState()
    LaunchedEffect(openEvent) {
        val (bandId, eventId, section) = openEvent ?: return@LaunchedEffect
        graph.incoming.pendingOpenEvent.value = null
        // The band can be gone by the time it opens (deleted, access lost): then nothing more happens.
        if (!graph.bands.hasStored(bandId)) {
            graph.incoming.sharedHandover.value = null
            return@LaunchedEffect
        }
        // First: opening the band shows another band, whose reset must not undo the event opened below. Only when it
        // switches: marked for a band already shown, the mark would stay and skip a later switch's reset.
        if (graph.bands.currentBandId != bandId) {
            graph.incoming.navigatedForBand = bandId
            graph.bands.open(bandId)
        }
        // Already on it (e.g. files shared from the gig's screen): it stays, and takes what was shared.
        val entry = navController.currentBackStackEntry
        if (entry?.destination?.hasRoute(EventRoute::class) == true && entry.toRoute<EventRoute>().eventId == eventId) {
            // At the section where the files went, in place: the screen stays, with its snackbar offering Undo.
            if (section != null) entry.savedStateHandle[AppNavigator.EVENT_SECTION] = section.name
            return@LaunchedEffect
        }
        // From the event list, like switching bands: Back must not lead into the other band's screens.
        navController.resetTo(EventsRoute)
        navController.navigate(EventRoute(eventId, section))
    }
    LaunchedEffect(state.me?.pictureUrl) { graph.account.sendPictureIfMissing(state.me) }
    SharedFilesDialog(graph.incoming, graph.bands, graph.attachments, state)
    val session = rememberBandSession(state, graph)
    val navigator = remember(navController, graph) { AppNavigator(navController) }

    // The band fund tab appears once someone holds the fund (leaders choose the holder in Settings).
    val fundEnabled = state.band.treasurerMemberId != null
    // Artists get their events and their costs only (ITERATION_4 §1.1).
    val artist = state.band.isArtist(state.userId)
    val tabs = Tab.entries.filter { (it != Tab.FUND || fundEnabled) && (!artist || it == Tab.EVENTS || it == Tab.MY_COSTS) }
    // E.g. after switching to a band without a band fund while on its tab.
    LaunchedEffect(fundEnabled) {
        if (!fundEnabled && navController.currentDestination?.hasRoute(FundRoute::class) == true) navController.resetTo(EventsRoute)
    }
    MainScaffold(navController, graph.sync, graph.bands, graph.membership, state.band.id, tabs) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> {
                EventsScreen(session, settings, navigator)
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
                // The section to show: the route's at first, or one asked for later (files shared to this event); cleared once shown.
                val asked by entry.savedStateHandle.getStateFlow(AppNavigator.EVENT_SECTION, route.section?.name).collectAsState()
                val screen = entry.screenViewModel { EventViewModel(route.eventId, session.bands.state) }
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
                SettingsScreen(
                    settingsFor(session, onBandGone = navigator::bandGone),
                    page,
                    // Only from the list while it is on screen, so a double tap does not open the page twice.
                    onOpenPage = { if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(SettingsRoute(it.name)) },
                    onOpenMembers = navigator::members,
                    onBack = navigator::back,
                )
            }
            composable<RosterRoute> { entry ->
                val roster = entry.screenViewModel {
                    val api = session.api
                    val bandId = session.band.id
                    RosterViewModel(
                        invitesOf = { api.invites(bandId).invites },
                        createInvite = { memberId, role -> api.createInvite(bandId, memberId, role) },
                        revokeInvite = { api.revokeInvite(bandId, it) },
                    )
                }
                RosterScreen(session, navigator, roster, openMember = entry.toRoute<RosterRoute>().memberId)
            }
            composable<SummaryRoute> { SummaryScreen(session, navigator) }
            composable<PlayedRoute> { PlayedScreen(session, navigator) }
            composable<HistoryRoute> { if (artist) BackToEvents(navController) else HistoryScreen(session, navigator) }
            composable<NewBandRoute> {
                NewBandScreen(defaultName, onCreate = { name, currency, yourName ->
                    graph.bands.createBand(name, currency, yourName)
                    graph.sync.requestSync()
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
