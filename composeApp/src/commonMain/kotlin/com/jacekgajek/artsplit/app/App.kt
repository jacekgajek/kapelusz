package com.jacekgajek.artsplit.app

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
import com.jacekgajek.artsplit.app.generated.band_was_deleted
import com.jacekgajek.artsplit.app.sync.LostBand
import com.jacekgajek.artsplit.app.generated.ok
import com.jacekgajek.artsplit.app.generated.sync_refused
import com.jacekgajek.artsplit.app.sync.SyncStatus
import com.jacekgajek.artsplit.app.ui.HistoryRoute
import com.jacekgajek.artsplit.app.ui.NewBandRoute
import com.jacekgajek.artsplit.app.ui.components.AndroidAppBanner
import com.jacekgajek.artsplit.app.ui.components.LocalTopBarState
import com.jacekgajek.artsplit.app.ui.components.SignInAgainDialog
import com.jacekgajek.artsplit.app.ui.components.TopBarState
import com.jacekgajek.artsplit.app.ui.components.labelRes
import com.jacekgajek.artsplit.app.ui.screens.NewBandScreen
import com.jacekgajek.artsplit.app.ui.screens.ZeroScreen
import com.jacekgajek.artsplit.ops.RejectionReason
import com.jacekgajek.artsplit.ops.RenameBand
import com.jacekgajek.artsplit.ops.SetTreasurer
import com.jacekgajek.artsplit.ops.SetAgendaTemplate
import com.jacekgajek.artsplit.model.AgendaTemplateItem
import com.jacekgajek.artsplit.model.effectiveAgendaTemplate
import com.jacekgajek.artsplit.model.Role
import com.jacekgajek.artsplit.ops.DisableBandFund
import com.jacekgajek.artsplit.domain.BandFund
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.components.language
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
import com.jacekgajek.artsplit.app.ui.screens.UpdateRequiredScreen
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
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.place_new_event
import com.jacekgajek.artsplit.app.generated.place_nothing
import com.jacekgajek.artsplit.app.generated.place_title
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.UpdateEvent
import org.jetbrains.compose.resources.pluralStringResource
import com.jacekgajek.artsplit.app.generated.reminder_prompt_not_now
import com.jacekgajek.artsplit.app.generated.reminder_prompt_yes
import com.jacekgajek.artsplit.app.generated.reminder_prompt_question
import com.jacekgajek.artsplit.app.generated.reminder_prompt_playing
import com.jacekgajek.artsplit.app.generated.reminder_prompt_added
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.data.today
import com.jacekgajek.artsplit.app.data.AppSettings
import com.jacekgajek.artsplit.app.platform.rememberNotificationPermission
import com.jacekgajek.artsplit.app.notify.NotificationRules

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
                val updateRequired by graph.updateRequired.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    if (updateRequired) {
                        UpdateRequiredScreen()
                    } else if (session == null) {
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
    var lostBand by remember { mutableStateOf<LostBand?>(null) }
    LaunchedEffect(graph) { graph.sync.lostAccess.collect { lostBand = it } }
    lostBand?.let { lost ->
        AlertDialog(
            onDismissRequest = { lostBand = null },
            text = { Text(stringResource(if (lost.deleted) Res.string.band_was_deleted else Res.string.lost_access, lost.name)) },
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
    // A tapped reminder or transfer notification (Android): open its gig, switching band if needed.
    val openEvent by graph.pendingOpenEvent.collectAsState()
    LaunchedEffect(openEvent) {
        val (bandId, eventId) = openEvent ?: return@LaunchedEffect
        graph.pendingOpenEvent.value = null
        // A notification can outlive its band (deleted, access lost): then the tap only opens the app.
        if (!graph.bands.hasStored(bandId)) return@LaunchedEffect
        if (graph.bands.currentBandId != bandId) graph.bands.open(bandId)
        // From the event list, like switching bands: Back must not lead into the other band's screens.
        navController.resetTo(EventsRoute)
        navController.navigate(EventRoute(eventId))
    }
    ReminderPrompt(graph, settings)
    SharedPlaceDialog(graph, state, onOpenEvent = { navController.navigate(EventRoute(it)) }, onNewEvent = { navController.resetTo(EventsRoute) })
    val toSettings = { navController.navigate(SettingsRoute) }
    val toRoster = { navController.navigate(RosterRoute) }
    val back: () -> Unit = { navController.popBackStack() }

    // The band fund tab appears once someone holds the fund (admins choose the holder in Settings).
    val fundEnabled = state.band.treasurerMemberId != null
    val tabs = Tab.entries.filter { it != Tab.FUND || fundEnabled }
    // E.g. after switching to a band without a band fund while on its tab.
    LaunchedEffect(fundEnabled) {
        if (!fundEnabled && navController.currentDestination?.hasRoute(FundRoute::class) == true) navController.resetTo(EventsRoute)
    }
    MainScaffold(navController, graph, tabs) {
        NavHost(navController, startDestination = EventsRoute, modifier = Modifier.fillMaxSize()) {
            composable<EventsRoute> {
                EventsScreen(graph, state, settings, { navController.navigate(EventRoute(it)) }, toSettings, toRoster)
            }
            composable<MyCostsRoute> { entry ->
                val everyone by entry.savedStateHandle.getStateFlow(SHOW_EVERYONE, false).collectAsState()
                MyCostsScreen(state, everyone, { entry.savedStateHandle[SHOW_EVERYONE] = it }, { navController.navigate(CostRoute(it)) }, toSettings, toRoster)
            }
            composable<SettleRoute> { SettleScreen(graph, state, { navController.navigate(EventRoute(it)) }, toSettings, toRoster) }
            composable<FundRoute> { BandFundScreen(graph, state, toSettings, toRoster, { navController.navigate(EventRoute(it)) }) }
            composable<EventRoute> { entry -> EventScreen(graph, state, entry.toRoute<EventRoute>().eventId, back) }
            composable<CostRoute> { entry ->
                CostScreen(graph, state, entry.toRoute<CostRoute>().costId, back, onAddedForOther = {
                    // Otherwise a lead adding a cost for someone else would not see it in "My costs".
                    navController.previousBackStackEntry?.savedStateHandle?.set(SHOW_EVERYONE, true)
                })
            }
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
                    onDisableFund = { graph.dispatch(DisableBandFund); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(DisableBandFund) && BandFund.canDisable(state.band) },
                    fundBalance = formatMoney(BandFund.balance(state.band), state.band.currency, language),
                    onDeleteBand = suspend { graph.deleteBand(); navController.resetTo(EventsRoute) }
                        .takeIf { state.me?.role == Role.OWNER },
                    otherMembers = state.band.members.count { it.userId != null && it.userId != state.userId },
                    autoUpdate = settings.autoUpdate,
                    appSettings = settings,
                    onAppSettings = { change -> graph.settings.update(change) },
                    // The secret menu (VERSION_2 §7.1), for admins of the band.
                    rawData = suspend { graph.api.rawState(state.band.id) }.takeIf { state.me?.role?.atLeast(Role.ADMIN) == true },
                    calendar = { change -> if (change == null) graph.api.calendar() else graph.api.changeCalendar(change) },
                    agendaTemplate = state.band.effectiveAgendaTemplate,
                    onAgendaTemplate = { items: List<AgendaTemplateItem> -> graph.dispatch(SetAgendaTemplate(items)); Unit }
                        .takeIf { Can(state.band, state.userId).allowed(SetAgendaTemplate(emptyList())) },
                    onAutoUpdate = { on -> graph.settings.update { it.copy(autoUpdate = on) } },
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

/** Saved state key of the "everyone's costs" switch on the costs tab. */
private const val SHOW_EVERYONE = "showEveryone"

/**
 * A place shared from Google Maps (Android, VERSION_2 §4.4): "Set the location of…" one of the upcoming events the
 * user may edit (nearest first), or "New event at this place". The name fills an empty location; the link replaces.
 */
@Composable
private fun SharedPlaceDialog(graph: AppGraph, state: BandState.Ready, onOpenEvent: (String) -> Unit, onNewEvent: () -> Unit) {
    val place by graph.pendingSharedPlace.collectAsState()
    val shared = place ?: return
    val band = state.band
    val can = Can(band, state.userId)
    val events = band.events.filter { !it.archived && it.date >= today() && can.editEvent(it) }.sortedBy { it.date }
    val dismiss = { graph.pendingSharedPlace.value = null }
    var rejected by remember(shared) { mutableStateOf<RejectionReason?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.place_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shared.name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                rejected?.let { Text(stringResource(it.labelRes()), color = MaterialTheme.colorScheme.error) }
                if (events.isEmpty() && !can.createEvent()) Text(stringResource(Res.string.place_nothing))
                events.forEach { event ->
                    TextButton(onClick = {
                        val rejection = graph.dispatch(
                            UpdateEvent(
                                event.id,
                                location = Change(shared.name).takeIf { event.location.isNullOrBlank() && shared.name != null },
                                mapUrl = Change(shared.url),
                            ),
                        )
                        rejected = rejection?.reason
                        if (rejection == null) {
                            dismiss()
                            onOpenEvent(event.id)
                        }
                    }) { Text("${event.name} · ${formatDate(event.date, language)}") }
                }
                if (can.createEvent()) {
                    TextButton(onClick = {
                        graph.pendingNewEventPlace.value = shared
                        dismiss()
                        onNewEvent()
                    }) { Text(stringResource(Res.string.place_new_event)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * Asks once, when the first upcoming gig of the user appears, whether they want reminders, naming who added them
 * (VERSION_2 §5.2). Android 13+ only; elsewhere no permission is needed or there are no notifications.
 */
@Composable
private fun ReminderPrompt(graph: AppGraph, settings: AppSettings) {
    val permission = rememberNotificationPermission() ?: return
    if (settings.notificationPromptDone || !settings.remindersEnabled || !permission.needsAsking) return
    val userId = graph.sessions.session.collectAsState().value?.userId ?: return
    val bandState by graph.bands.state.collectAsState()
    val (band, event) = remember(bandState) { NotificationRules.firstUpcomingGig(graph.bands.allBands(), userId, today()) } ?: return
    val me = band.memberOfUser(userId) ?: return
    val adder = event.participant(me.id)?.addedBy?.takeIf { it != me.id }?.let { band.member(it)?.name }
    val days = settings.reminderDays.minOrNull() ?: 1
    val date = formatDate(event.date, language)
    val text = if (adder != null) stringResource(Res.string.reminder_prompt_added, adder, event.name, date)
    else stringResource(Res.string.reminder_prompt_playing, event.name, date)
    AlertDialog(
        onDismissRequest = {},
        text = { Text(text + " " + pluralStringResource(Res.plurals.reminder_prompt_question, days, days)) },
        confirmButton = {
            TextButton(onClick = {
                // Answered now, so a screen rotation during Android's dialog cannot bring the prompt back.
                graph.settings.update { it.copy(notificationPromptDone = true) }
                permission.request { granted -> graph.settings.update { it.copy(remindersEnabled = granted) } }
            }) { Text(stringResource(Res.string.reminder_prompt_yes)) }
        },
        dismissButton = {
            TextButton(onClick = { graph.settings.update { it.copy(notificationPromptDone = true, remindersEnabled = false) } }) {
                Text(stringResource(Res.string.reminder_prompt_not_now))
            }
        },
    )
}

/** Navigates to [route] and drops everything else from the back stack. */
private fun NavHostController.resetTo(route: Any) = navigate(route) {
    popUpTo(graph.id) { inclusive = true }
}

/** Bottom navigation on phones, a rail on wide screens; shown only on the tab screens. */
@Composable
private fun MainScaffold(navController: NavHostController, graph: AppGraph, tabs: List<Tab>, content: @Composable () -> Unit) {
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
                Column {
                    if (current != null) AndroidAppBanner(autoUpdate = graph.settings.settings.collectAsState().value.autoUpdate)
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
