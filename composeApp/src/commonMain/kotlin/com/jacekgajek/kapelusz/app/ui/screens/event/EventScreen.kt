package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.jacekgajek.kapelusz.app.data.Attachments
import com.jacekgajek.kapelusz.app.data.EventTab
import com.jacekgajek.kapelusz.app.data.Incoming
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.add_place
import com.jacekgajek.kapelusz.app.generated.add_times
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.attachment_hint_event
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.event_files
import com.jacekgajek.kapelusz.app.generated.event_not_found
import com.jacekgajek.kapelusz.app.generated.fund_amount_from_hint
import com.jacekgajek.kapelusz.app.generated.fund_direction_from
import com.jacekgajek.kapelusz.app.generated.fund_direction_to
import com.jacekgajek.kapelusz.app.generated.navigate
import com.jacekgajek.kapelusz.app.generated.problem_loss
import com.jacekgajek.kapelusz.app.generated.problem_loss_fixed
import com.jacekgajek.kapelusz.app.generated.problem_no_shares
import com.jacekgajek.kapelusz.app.generated.problem_no_treasurer
import com.jacekgajek.kapelusz.app.generated.problem_over_percent
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.share_attach_failed
import com.jacekgajek.kapelusz.app.generated.share_attached
import com.jacekgajek.kapelusz.app.generated.tab_gig
import com.jacekgajek.kapelusz.app.generated.tab_money
import com.jacekgajek.kapelusz.app.generated.tab_settlement
import com.jacekgajek.kapelusz.app.generated.total_awaited
import com.jacekgajek.kapelusz.app.generated.total_from_fund
import com.jacekgajek.kapelusz.app.generated.total_fund
import com.jacekgajek.kapelusz.app.generated.total_income
import com.jacekgajek.kapelusz.app.generated.total_net
import com.jacekgajek.kapelusz.app.generated.total_refunds
import com.jacekgajek.kapelusz.app.generated.undo
import com.jacekgajek.kapelusz.app.generated.when_where
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.EventNav
import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.AmountRow
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.Choice
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.EmptyState
import com.jacekgajek.kapelusz.app.ui.components.FundCutEditor
import com.jacekgajek.kapelusz.app.ui.components.MAX_LIST_WIDTH
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.SegmentedChoice
import com.jacekgajek.kapelusz.app.ui.components.StatusChip
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberFundCutEditorState
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.format.formatDate
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.app.ui.screens.AttachmentsSection
import com.jacekgajek.kapelusz.app.ui.text.agendaLine
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.settlement.Settlement
import com.jacekgajek.kapelusz.settlement.SettlementProblem
import com.jacekgajek.kapelusz.text.Places
import com.jacekgajek.kapelusz.view.BandView
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Everything a section of the event screen needs. */
@Stable
class EventContext(
    /** The attachment files of the event, its costs and collected money. */
    val fileStore: Attachments,
    /** Shared files on their way to a new collected entry. */
    val incoming: Incoming,
    val view: BandView,
    val event: Event,
    val settlement: Settlement,
    val me: Member?,
    val actions: Actions,
    val can: Capabilities,
    /** Opens Band members at [memberId]'s dialog, to invite them (ITERATION_5 §12). */
    val onInviteMember: (memberId: String) -> Unit = {},
) {
    val band: Band get() = view.band
    val currency: String get() = band.currency
}

@Composable
fun EventScreen(
    session: BandSession,
    nav: EventNav,
    screen: EventViewModel,
    section: EventSection? = null,
    onSectionShow: () -> Unit = {},
) {
    val eventId = screen.eventId
    val state = session.state
    val api = session.api
    val bands = session.bands
    val sync = session.sync
    val preferences = session.settings
    val incoming = session.incoming
    val fileStore = session.attachments
    val onBack: () -> Unit = nav::back
    val onAddCost: (String) -> Unit = nav::addCost
    val onOpenCost: (String) -> Unit = nav::openCost
    val onInviteMember: (String) -> Unit = nav::inviteMember
    val messages = rememberMessages()
    val actions = rememberActions(session.changes, messages)
    val artist = screen.artist
    val event = screen.event
    val settlement = screen.settlement
    val shareSettlement = rememberShareSettlement(state.band, event, settlement, messages.snackbar)
    // Pages (ITERATION_5 §4.4). Each event opens on the page last used there; swiping or the tabs switch, and each page
    // keeps its own scroll position.
    val bandId = state.band.id
    val tabs = screen.tabs
    val stored = preferences.settings.collectAsState().value.eventTab(bandId, eventId)
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(stored).coerceAtLeast(0)) { tabs.size }
    val listStates = tabs.associateWith { rememberLazyListState() }
    LaunchedEffect(pagerState, tabs) {
        // getOrNull: a role changing to Artist meanwhile takes a page away before the pager catches up.
        snapshotFlow { pagerState.settledPage }.collect { page -> tabs.getOrNull(page)?.let { tab -> preferences.update { it.withEventTab(bandId, eventId, tab) } } }
    }
    // The page and place of one section; not for artists, whose pages have other sections.
    suspend fun scrollTo(section: EventSection) {
        val tab = section.tabs.first()
        pagerState.scrollToPage(tabs.indexOf(tab))
        // Once the page is laid out (every section is one item, shown or not).
        val sections = EventSection.on(tab)
        val list = listStates.getValue(tab)
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it == sections.size }
        list.scrollToItem(sections.indexOf(section))
    }
    // Opened at, or asked to show, one of its sections (where shared files went, the refund a cost is in): once each.
    val sectionShown by rememberUpdatedState(onSectionShow)
    LaunchedEffect(section, event != null) {
        if (section == null || event == null) return@LaunchedEffect
        // Artists' pages have other sections: just the page (e.g. Money, from a cost's form).
        if (!artist) scrollTo(section) else section.tabs.firstOrNull { it in tabs }?.let { pagerState.scrollToPage(tabs.indexOf(it)) }
        sectionShown()
    }
    // Files shared to Kapelusz (ITERATION_6 §1) on their way to the new cost that takes them. Only by the screen in
    // front: one on its way out (the same event opened again) would take them along.
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val handover by incoming.sharedHandover.collectAsState()
    LaunchedEffect(handover, event != null, resumed) {
        if (!resumed) return@LaunchedEffect
        when (screen.handoverAction(handover)) {
            HandoverAction.DROP -> incoming.sharedHandover.value = null
            HandoverAction.ADD_COST -> onAddCost(eventId)
            null -> Unit
        }
    }
    // Taken over from incoming first: clearing it there must not cancel the snackbar showing here.
    val sharedAttached by incoming.sharedAttached.collectAsState()
    LaunchedEffect(sharedAttached, resumed) {
        if (resumed && screen.takeAttached(sharedAttached, state.band.id)) incoming.sharedAttached.value = null
    }
    val attached = screen.attached
    val attachedText = attached?.let { pluralStringResource(Res.plurals.share_attached, it.attachmentIds.size, it.attachmentIds.size) }
    val attachFailed = stringResource(Res.string.share_attach_failed)
    val undoLabel = stringResource(Res.string.undo)
    LaunchedEffect(attached) {
        val done = attached ?: return@LaunchedEffect
        // Forgotten before it shows: left half-shown (the screen replaced, a rotation), it would otherwise show again.
        screen.attachedShown()
        if (done.attachmentIds.isEmpty()) {
            messages.snackbar.showSnackbar(attachFailed)
        } else {
            val message = if (done.failed > 0) "$attachedText. $attachFailed" else attachedText.orEmpty()
            // With an action a snackbar stays until tapped by default: a while, with a cross to close it sooner.
            val result = messages.snackbar.showSnackbar(message, undoLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            val undone = result == SnackbarResult.ActionPerformed
            if (undone && bands.currentBandId == done.bandId) done.attachmentIds.forEach { fileStore.remove(done.owner, it) }
        }
    }

    AppScaffold(
        messages,
        topBar = {
            BackTopBar(event?.name ?: "", onBack) {
                if (event != null && settlement != null) {
                    val can = session.can
                    // For everyone (Copy link); archiving and deleting for who may. Also on archived events, which are
                    // otherwise read-only: restoring them is in it.
                    EventMenu(event, settlement, actions, manage = can.archiveEvents(), onDeleted = onBack)
                }
            }
        },
    ) { padding ->
        if (event == null) {
            EmptyState(stringResource(Res.string.event_not_found), Modifier.padding(padding))
            return@AppScaffold
        }
        if (settlement == null) return@AppScaffold
        // Once per band state: the sections and their dialogs all take it.
        val context = remember(state, event, settlement, actions, session, nav) {
            EventContext(fileStore, incoming, state.view, event, settlement, state.me, actions, session.can, onInviteMember)
        }
        val shareInfo = rememberShareInfo(state.band, event, messages.snackbar)
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            EventTabs(tabs, pagerState)
            HorizontalPager(pagerState, Modifier.fillMaxSize(), key = { tabs[it] }) { page ->
                val tab = tabs[page]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        Modifier.widthIn(max = MAX_LIST_WIDTH).fillMaxWidth(),
                        state = listStates.getValue(tab),
                        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                    ) {
                        if (artist) {
                            // Artists see fewer sections: nothing scrolls to one by name here.
                            if (tab == EventTab.GIG) {
                                item("when") { WhenAndWhere(context, onEdit = null) }
                                item("details") { DetailsSection(context) }
                                item("files") { FilesSection(context) }
                                item("lineup") { ArtistLineup(context) }
                                item("share") { ShareInfoButton(shareInfo) }
                            } else {
                                item("payout") { ArtistPayout(context) }
                                item("transfers") { ArtistTransfers(context) }
                                item("costs") { ArtistCosts(context, onOpenCost, onAddCost = { onAddCost(eventId) }) }
                            }
                            return@LazyColumn
                        }
                        // The page's sections, in the order of [EventSection]: To settle is found by its index.
                        EventSection.on(tab).forEach { section ->
                            item(key = section.name) {
                                when (section) {
                                    EventSection.WHEN -> {
                                        val edit = { screen.editing = true }
                                        WhenAndWhere(context, onEdit = edit.takeIf { context.can.editEvent(event) })
                                    }

                                    EventSection.DETAILS -> DetailsSection(context)

                                    EventSection.FILES -> FilesSection(context)

                                    EventSection.PARTICIPANTS -> ParticipantsSection(context)

                                    EventSection.SHARE_INFO -> ShareInfoButton(shareInfo)

                                    EventSection.STATUS -> StatusChip(context.settlement.status, event.archived, Modifier.fillMaxWidth().padding(top = 8.dp))

                                    EventSection.TOTALS -> Totals(context)

                                    EventSection.COLLECTED -> CollectedSection(context)

                                    EventSection.REFUNDS -> RefundsSection(context, onAddCost = { onAddCost(eventId) }, onOpenCost = onOpenCost)

                                    EventSection.PROBLEMS -> Problems(context)

                                    EventSection.SUMMARY -> SummarySection(context)

                                    EventSection.TO_SETTLE -> TransfersSection(context, shareSettlement)

                                    EventSection.HISTORY -> HistorySection(context, api, bands, sync)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (screen.editing) {
            val places = rememberPlaceLookup(api, state.band, state.userId)
            EditDetailsDialog(state.band, event, actions, places, onDismiss = { screen.editing = false })
        }
    }
}

/** The pages' tabs (ITERATION_5 §4.4): text and the underline, no checkmark or borders, so three labels fit a phone. */
@Composable
private fun EventTabs(tabs: List<EventTab>, pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    PrimaryTabRow(selectedTabIndex = pagerState.currentPage, modifier = Modifier.widthIn(max = MAX_LIST_WIDTH).fillMaxWidth()) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(stringResource(tab.label())) },
            )
        }
    }
}

private fun EventTab.label() = when (this) {
    EventTab.GIG -> Res.string.tab_gig
    EventTab.MONEY -> Res.string.tab_money
    EventTab.SETTLEMENT -> Res.string.tab_settlement
}

/**
 * The top of the gig page: date and place with Navigate beside them, then the set times. Changed in place like the
 * sections under it (ITERATION_5 §4.9): its ✎ opens the event's dialog (also its name); what is not set yet is offered.
 */
@Composable
private fun WhenAndWhere(context: EventContext, onEdit: (() -> Unit)?) {
    val event = context.event
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(Res.string.when_where), Modifier.weight(1f))
        if (onEdit != null) EditButton(onClick = onEdit)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(formatDate(event.date, language), event.location).joinToString(" · "),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            // Google Maps: the stored link (the exact pin), otherwise directions to the location text.
            Places.navigateUrl(event)?.let { url ->
                val uriHandler = LocalUriHandler.current
                AssistChip(
                    onClick = { uriHandler.openUri(url) },
                    label = { Text(stringResource(Res.string.navigate)) },
                    leadingIcon = { Icon(Icons.Default.Directions, null) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        val times = event.agendaLine()
        times?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (onEdit != null && (event.location.isNullOrBlank() || times == null)) {
            Row {
                if (event.location.isNullOrBlank()) TextButton(onClick = onEdit) { Text(stringResource(Res.string.add_place)) }
                if (times == null) TextButton(onClick = onEdit) { Text(stringResource(Res.string.add_times)) }
            }
        }
    }
}

/** The event's money at a glance: what came in, what goes to or from the fund and to refunds, and what is shared. */
@Composable
private fun Totals(context: EventContext) {
    val event = context.event
    val s = context.settlement
    var editingFund by remember { mutableStateOf(false) }
    if (editingFund) FundAmountDialog(context, onDismiss = { editingFund = false })
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AmountRow(stringResource(Res.string.total_income), s.income)
            // Part of the income is a fee the organiser has not paid yet (ITERATION_4 §2.2).
            if (event.awaited.isPositive) AmountRow(stringResource(Res.string.total_awaited), event.awaited)
            val canSetFund = !event.archived && context.band.treasurerMemberId != null && context.can.allowed(SetFundAmount(event.id, Money(1)))
            if (!s.fundAmount.isZero || event.fundPercent != null || canSetFund) {
                // Shown as what it does to the amount to share: money to the fund is taken off, money from it is added.
                AmountRow(
                    stringResource(if (s.fundAmount.isNegative) Res.string.total_from_fund else Res.string.total_fund) +
                        (event.fundPercent?.let { " ($it%)" } ?: ""),
                    -s.fundAmount,
                    onEdit = { editingFund = true }.takeIf { canSetFund },
                )
            }
            if (!s.refundsTotal.isZero) AmountRow(stringResource(Res.string.total_refunds), -s.refundsTotal)
            AmountRow(stringResource(Res.string.total_net), s.net, bold = true)
        }
    }
}

/**
 * How much of this event goes to the band fund, or comes from it to top up the payouts. The direction is a choice
 * rather than a minus sign: phone keyboards for amounts often have no minus key. To the fund, it may be a percentage
 * of the profit instead (ITERATION_7 §5.2); from it, always an amount.
 */
@Composable
private fun FundAmountDialog(context: EventContext, onDismiss: () -> Unit) {
    val event = context.event
    var fromFund by remember { mutableStateOf(event.fundAmount.isNegative) }
    val editor = rememberFundCutEditorState(
        when {
            event.fundPercent != null -> FundCut(percent = event.fundPercent)
            event.fundAmount.isZero -> null
            else -> FundCut(amount = Money(abs(event.fundAmount.minor)))
        },
    )
    val profit = event.income - context.settlement.refundsTotal
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.band_fund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val directions = listOf(
                    Choice(false, stringResource(Res.string.fund_direction_to)),
                    Choice(true, stringResource(Res.string.fund_direction_from)),
                )
                SegmentedChoice(directions, fromFund, { fromFund = it })
                if (fromFund) {
                    // The editor's amount: switching back keeps a percentage chosen before.
                    MoneyField(editor.amount, { editor.amount = it }, stringResource(Res.string.amount))
                    Text(stringResource(Res.string.fund_amount_from_hint), style = MaterialTheme.typography.bodySmall)
                } else {
                    FundCutEditor(editor, context.currency, profit)
                }
                DialogNotice(context.actions)
            }
        },
        confirmButton = {
            val valid = if (fromFund) editor.parsedAmount != null else editor.valid
            TextButton(enabled = valid, onClick = {
                val op = when {
                    fromFund -> SetFundAmount(event.id, -editor.parsedAmount!!)
                    editor.byPercent -> SetFundAmount(event.id, Money.ZERO, editor.percent)
                    else -> SetFundAmount(event.id, editor.parsedAmount!!)
                }
                // As the reducer stores it: 0% is nothing, like an amount of 0.
                val unchanged = op.percent?.takeIf { it > 0 } == event.fundPercent && (if (op.percent != null) Money.ZERO else op.amount) == event.fundAmount
                if (unchanged || context.actions.run(op)) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun Problems(context: EventContext) {
    val problems = context.settlement.problems
    if (problems.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            problems.forEach { problem ->
                Text(
                    when (problem) {
                        // Fixed shares and percentages count towards the loss too (ITERATION_3 §2.4), so lowering them is a way out.
                        SettlementProblem.LOSS -> stringResource(
                            if (context.settlement.fixedTotal.isZero && context.settlement.percentTotal.isZero) {
                                Res.string.problem_loss
                            } else {
                                Res.string.problem_loss_fixed
                            },
                            formatMoney(context.settlement.shortfall, context.currency, language),
                        )

                        SettlementProblem.NO_SHARES -> stringResource(Res.string.problem_no_shares)

                        SettlementProblem.OVER_PERCENT -> stringResource(
                            Res.string.problem_over_percent,
                            "${context.event.participants.sumOf { it.percent ?: 0 } + (context.event.fundPercent ?: 0)}%",
                        )

                        SettlementProblem.NO_TREASURER -> stringResource(Res.string.problem_no_treasurer)
                    },
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * The gig's own files (ITERATION_4 §2.1): contract, rider, stage plan. Leads add, rename and remove them; everyone,
 * artists too, can open them.
 */
@Composable
private fun FilesSection(context: EventContext) {
    val event = context.event
    val editable = context.can.editEvent(event)
    if (event.attachments.isEmpty() && !editable) return
    SectionTitle(stringResource(Res.string.event_files))
    AttachmentsSection(
        context.fileStore, AttachmentOwner.OfEvent(event.id), event.attachments,
        drafts = emptyList(), onDrafts = {}, editable = editable, actions = context.actions,
        max = MAX_EVENT_ATTACHMENTS, hint = Res.string.attachment_hint_event, title = null, undoRemoval = true,
    )
}
