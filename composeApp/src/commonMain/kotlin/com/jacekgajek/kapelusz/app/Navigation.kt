package com.jacekgajek.kapelusz.app

import com.jacekgajek.kapelusz.ops.SetBlikPhone
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.sync_refused
import com.jacekgajek.kapelusz.app.generated.tab_events
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.tab_my_costs
import com.jacekgajek.kapelusz.app.generated.tab_settle
import com.jacekgajek.kapelusz.app.ui.CostRoute
import com.jacekgajek.kapelusz.app.ui.EventRoute
import com.jacekgajek.kapelusz.app.ui.EventsRoute
import com.jacekgajek.kapelusz.app.ui.FundRoute
import com.jacekgajek.kapelusz.app.ui.HistoryRoute
import com.jacekgajek.kapelusz.app.ui.SummaryRoute
import com.jacekgajek.kapelusz.app.ui.screens.SummaryScreen
import com.jacekgajek.kapelusz.app.ui.MyCostsRoute
import com.jacekgajek.kapelusz.app.ui.NewBandRoute
import com.jacekgajek.kapelusz.app.ui.RosterRoute
import com.jacekgajek.kapelusz.app.ui.SettingsRoute
import com.jacekgajek.kapelusz.app.ui.SettleRoute
import com.jacekgajek.kapelusz.app.ui.components.AndroidAppBanner
import com.jacekgajek.kapelusz.app.ui.components.StuckPushBanner
import com.jacekgajek.kapelusz.app.ui.components.labelRes
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.isArtist
import com.jacekgajek.kapelusz.app.ui.screens.BandFundScreen
import com.jacekgajek.kapelusz.app.ui.screens.CostScreen
import com.jacekgajek.kapelusz.app.ui.screens.EventsScreen
import com.jacekgajek.kapelusz.app.ui.screens.HistoryScreen
import com.jacekgajek.kapelusz.app.ui.screens.MyCostsScreen
import com.jacekgajek.kapelusz.app.ui.screens.NewBandScreen
import com.jacekgajek.kapelusz.app.ui.screens.RosterScreen
import com.jacekgajek.kapelusz.app.ui.screens.SettleScreen
import com.jacekgajek.kapelusz.app.ui.screens.event.EventScreen
import com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsPage
import com.jacekgajek.kapelusz.app.ui.screens.settings.SettingsScreen
import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetAgendaTemplate
import com.jacekgajek.kapelusz.ops.SetTreasurer
import kotlinx.coroutines.launch
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
    val signIns by graph.signIns.collectAsState()
    // Each sign-in once: this screen is composed again on a language change or (Android) rotation, which must keep
    // where the user is.
    LaunchedEffect(signIns) {
        if (signIns > graph.handledSignIns) {
            graph.handledSignIns = signIns
            navController.resetTo(EventsRoute)
        }
    }
    // An event to open (e.g. an event link): open it, switching band if needed.
    val openEvent by graph.pendingOpenEvent.collectAsState()
    LaunchedEffect(openEvent) {
        val (bandId, eventId) = openEvent ?: return@LaunchedEffect
        graph.pendingOpenEvent.value = null
        // A link can outlive its band (deleted, access lost): then it only opens the app.
        if (!graph.bands.hasStored(bandId)) {
            graph.sharedHandover.value = null
            return@LaunchedEffect
        }
        // First: opening the band shows another band, whose reset must not undo the event opened below. Only when it
        // switches: marked for a band already shown, the mark would stay and skip a later switch's reset.
        if (graph.bands.currentBandId != bandId) {
            graph.navigatedForBand = bandId
            graph.bands.open(bandId)
        }
        // Already on it (e.g. files shared from the gig's screen): it stays, and takes what was shared.
        val entry = navController.currentBackStackEntry
        if (entry?.destination?.hasRoute(EventRoute::class) == true && entry.toRoute<EventRoute>().eventId == eventId) return@LaunchedEffect
        // From the event list, like switching bands: Back must not lead into the other band's screens.
        navController.resetTo(EventsRoute)
        navController.navigate(EventRoute(eventId))
    }
    LaunchedEffect(state.me?.pictureUrl) { graph.sendPictureIfMissing(state.me) }
    SharedFilesDialog(graph, state)
    val toSettings = { navController.navigate(SettingsRoute()) }
    val toRoster = { navController.navigate(RosterRoute()) }
    // Only from a screen that is fully shown, and never past the first one: a second quick tap during the fade-out
    // would pop the list too and leave a blank screen.
    val back: () -> Unit = {
        if (navController.previousBackStackEntry != null && navController.currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            navController.popBackStack()
        }
    }

    // The band fund tab appears once someone holds the fund (leaders choose the holder in Settings).
    val fundEnabled = state.band.treasurerMemberId != null
    // Artists get their events and their costs only (ITERATION_4 §1.1).
    val artist = state.band.isArtist(state.userId)
    val tabs = Tab.entries.filter { (it != Tab.FUND || fundEnabled) && (!artist || it == Tab.EVENTS || it == Tab.MY_COSTS) }
    // E.g. after switching to a band without a band fund while on its tab.
    LaunchedEffect(fundEnabled) {
        if (!fundEnabled && navController.currentDestination?.hasRoute(FundRoute::class) == true) navController.resetTo(EventsRoute)
    }
    MainScaffold(navController, graph, state.band.id, tabs) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> {
                EventsScreen(graph, state, settings, { navController.navigate(EventRoute(it)) }, toSettings, toRoster)
            }
            composable<MyCostsRoute> { entry ->
                val everyone by entry.savedStateHandle.getStateFlow(SHOW_EVERYONE, false).collectAsState()
                MyCostsScreen(graph, state, everyone, { entry.savedStateHandle[SHOW_EVERYONE] = it }, { navController.navigate(CostRoute(it)) }, toSettings, toRoster)
            }
            composable<SettleRoute> {
                if (artist) BackToEvents(navController) else SettleScreen(graph, state, { navController.navigate(EventRoute(it)) }, toSettings, toRoster)
            }
            composable<FundRoute> {
                if (artist) BackToEvents(navController) else BandFundScreen(graph, state, toSettings, toRoster, { navController.navigate(EventRoute(it)) })
            }
            composable<EventRoute> { entry -> EventScreen(graph, state, entry.toRoute<EventRoute>().eventId, back, onAddCost = { navController.navigate(CostRoute(forEventId = it)) }, onOpenCost = { navController.navigate(CostRoute(it)) }, onInviteMember = { navController.navigate(RosterRoute(it)) }) }
            composable<CostRoute> { entry ->
                val route = entry.toRoute<CostRoute>()
                // Artists open only their own costs (e.g. not from a web address someone shared).
                if (artist && route.costId != null && state.band.cost(route.costId)?.memberId.let { it != null && it != state.me?.id }) {
                    BackToEvents(navController)
                    return@composable
                }
                CostScreen(graph, state, route.costId, back, forEventId = route.forEventId, onOpenEvent = { eventId ->
                    // At its Money page, where the refund is.
                    graph.pendingEventSection.value = eventId to "REFUNDS"
                    navController.navigate(EventRoute(eventId))
                }, onAddedForOther = {
                    // Otherwise a lead adding a cost for someone else would not see it in "My costs".
                    navController.previousBackStackEntry?.savedStateHandle?.set(SHOW_EVERYONE, true)
                })
            }
            composable<SettingsRoute> { entry ->
                val page = entry.toRoute<SettingsRoute>().page?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
                val session by graph.sessions.session.collectAsState()
                SettingsScreen(
                    language = settings.language,
                    onLanguage = { code -> graph.settings.update { it.copy(language = code) } },
                    account = session?.let { it.email ?: it.name },
                    accountPicture = state.me?.pictureUrl,
                    onSignOut = { graph.signOutInBackground() },
                    onSignOutEverywhere = { graph.signOutEverywhere() },
                    bandName = state.band.name,
                    onRenameBand = { name: String -> graph.dispatch(RenameBand(name)); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(RenameBand("probe")) },
                    treasurer = state.band.treasurerMemberId,
                    treasurerName = state.band.treasurerMemberId?.let { state.band.member(it)?.name },
                    // Artists hold no money and viewers record no transfers: neither can hold the fund (V5-M1 C6a).
                    treasurerOptions = state.band.members.filter { !it.archived && it.role != Role.ARTIST && it.role != Role.VIEWER }.map { it.id to it.name },
                    onTreasurer = { id: String -> graph.dispatch(SetTreasurer(id)); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(SetTreasurer("probe")) },
                    onDisableFund = { graph.dispatch(DisableBandFund); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(DisableBandFund) && BandFund.canDisable(state.band) },
                    fundBalance = formatMoney(BandFund.balance(state.band), state.band.currency, language),
                    onDeleteBand = suspend { graph.deleteBand(); navController.resetTo(EventsRoute) }
                        .takeIf { state.me?.role == Role.OWNER },
                    otherMembers = state.band.members.count { it.userId != null && it.userId != state.userId },
                    autoUpdate = settings.autoUpdate,
                    artist = artist,
                    // The secret menu (ITERATION_2 §7.1), for the band's leaders.
                    rawData = suspend { graph.api.rawState(state.band.id) }.takeIf { state.me?.role?.atLeast(Role.LEAD) == true },
                    calendar = { change -> if (change == null) graph.api.calendar() else graph.api.changeCalendar(change) },
                    agendaTemplate = state.band.effectiveAgendaTemplate,
                    agendaTemplateStored = state.band.agendaTemplate != null,
                    onAgendaTemplate = { items: List<AgendaTemplateItem> -> graph.dispatch(SetAgendaTemplate(items)); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(SetAgendaTemplate(emptyList())) },
                    onAutoUpdate = { on -> graph.settings.update { it.copy(autoUpdate = on) } },
                    page = page,
                    // Only from the list while it is on screen, so a double tap does not open the page twice.
                    onOpenPage = { if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(SettingsRoute(it.name)) },
                    onBack = back,
                    blikPhone = state.me?.blikPhone,
                    onBlikPhone = state.me?.let { me -> { phone: String? -> graph.dispatch(SetBlikPhone(me.id, phone)); Unit } }
                        ?.takeIf { state.me?.let { Can(state.band, state.userId).allowed(SetBlikPhone(it.id, null)) } == true },
                )
            }
            composable<RosterRoute> { entry ->
                if (artist) BackToEvents(navController) else RosterScreen(graph, state, back, openMember = entry.toRoute<RosterRoute>().memberId)
            }
            composable<SummaryRoute> { SummaryScreen(graph, state, back, onInviteMember = { navController.navigate(RosterRoute(it)) }) }
            composable<HistoryRoute> { if (artist) BackToEvents(navController) else HistoryScreen(graph, state, back) }
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

/** Saved state key of the "everyone's costs" switch on the costs tab. */
private const val SHOW_EVERYONE = "showEveryone"

/** Navigates to [route] and drops everything else from the back stack. */
internal fun NavHostController.resetTo(route: Any) {
    // The tabs' saved back stacks too (V5-M1 C2a): after a band switch, a tab must not bring back an event or a cost
    // of the band before.
    Tab.entries.forEach { clearBackStack(it.route) }
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

/** Bottom navigation on phones, a rail on wide screens; shown only on the tab screens. */
@Composable
private fun MainScaffold(navController: NavHostController, graph: AppGraph, bandId: String, tabs: List<Tab>, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    // Changes the server refused during sync (e.g. a role changed meanwhile).
    val snackbar = remember { SnackbarHostState() }
    val reasonTexts = RejectionReason.entries.associateWith { stringResource(it.labelRes()) }
    val refusedPrefix = stringResource(Res.string.sync_refused)
    LaunchedEffect(graph) {
        graph.sync.rejections.collect { snackbar.showSnackbar("$refusedPrefix ${reasonTexts.getValue(it.reason)}") }
    }
    val current = Tab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.route::class) == true }
    // Changes the server refuses as a whole (ITERATION_3 R9).
    val stuck = graph.sync.stuck.collectAsState().value[bandId]
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
                Column {
                    if (current != null && stuck != null) {
                        StuckPushBanner(stuck, { graph.bands.pendingAsJson(bandId) }, { graph.discardStuck(bandId) }, { snackbar.showSnackbar(it) })
                    }
                    if (current != null) AndroidAppBanner(autoUpdate = graph.settings.settings.collectAsState().value.autoUpdate, eventList = current == Tab.EVENTS)
                    if (!wide && current != null) NavigationBar {
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
