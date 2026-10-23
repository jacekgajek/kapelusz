package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.attachment_hint_event
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.fund_amount_from_hint
import com.jacekgajek.kapelusz.app.generated.fund_amount_to_hint
import com.jacekgajek.kapelusz.app.generated.fund_direction_from
import com.jacekgajek.kapelusz.app.generated.fund_direction_to
import com.jacekgajek.kapelusz.app.generated.total_awaited
import com.jacekgajek.kapelusz.app.generated.total_from_fund
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import kotlin.math.abs
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.ops.SetFundAmount
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.edit_details
import com.jacekgajek.kapelusz.app.generated.event_files
import com.jacekgajek.kapelusz.app.generated.event_not_found
import com.jacekgajek.kapelusz.app.generated.problem_loss
import com.jacekgajek.kapelusz.app.generated.problem_loss_fixed
import com.jacekgajek.kapelusz.app.generated.problem_no_shares
import com.jacekgajek.kapelusz.app.generated.problem_no_treasurer
import com.jacekgajek.kapelusz.app.generated.total_fund
import com.jacekgajek.kapelusz.app.generated.total_income
import com.jacekgajek.kapelusz.app.generated.total_net
import com.jacekgajek.kapelusz.app.generated.total_refunds
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.BackTopBar
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.StatusChip
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.agendaLine
import com.jacekgajek.kapelusz.domain.Places
import com.jacekgajek.kapelusz.app.generated.navigate
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.AssistChip
import androidx.compose.ui.platform.LocalUriHandler
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.domain.Settlement
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.domain.SettlementProblem
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.MAX_EVENT_ATTACHMENTS
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.isArtist
import com.jacekgajek.kapelusz.app.ui.screens.AttachmentsSection
import org.jetbrains.compose.resources.stringResource

/** Everything a section of the event screen needs. */
class EventContext(
    val graph: AppGraph,
    val band: Band,
    val event: Event,
    val settlement: Settlement,
    val me: Member?,
    val actions: Actions,
    val can: Can,
) {
    val currency: String get() = band.currency
}

@Composable
fun EventScreen(
    graph: AppGraph,
    state: BandState.Ready,
    eventId: String,
    onBack: () -> Unit,
    onAddCost: (eventId: String) -> Unit = {},
    onOpenCost: (costId: String) -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    // Artists only open the events they play, not archived ones (ITERATION_4 §1.1).
    val artist = state.band.isArtist(state.userId)
    val event = state.band.event(eventId)?.takeUnless { artist && (it.archived || state.me?.let { me -> it.participant(me.id) } == null) }
    var editing by remember { mutableStateOf(false) }
    val settlement = event?.let { SettlementCalculator.settle(state.band, it) }
    val shareSettlement = rememberShareSettlement(state.band, event, settlement, snackbar)
    val listState = rememberLazyListState()
    // Opened from a reminder about an open debt (ITERATION_3 §2.2): straight to To settle.
    val toSettle by graph.pendingToSettle.collectAsState()
    LaunchedEffect(toSettle, event != null) {
        if (toSettle != eventId || event == null) return@LaunchedEffect
        graph.pendingToSettle.value = null
        listState.scrollToItem(EventSection.TO_SETTLE.ordinal)
    }

    Scaffold(
        topBar = {
            BackTopBar(event?.name ?: "", onBack) {
                if (!artist) shareSettlement?.let { ShareSettlementIcon(it) }
                if (event != null && settlement != null) {
                    val can = Can(state.band, state.userId)
                    if (can.editEvent(event)) IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, stringResource(Res.string.edit_details)) }
                    // Also on archived events, which are otherwise read-only: restoring them is in it.
                    if (can.archiveEvents()) EventMenu(event, settlement, actions, onDeleted = onBack)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (event == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.event_not_found))
            }
            return@Scaffold
        }
        val context = EventContext(graph, state.band, event, settlement ?: return@Scaffold, state.me, actions, Can(state.band, state.userId))
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            ) {
                if (artist) {
                    // Artists get no debt reminders, so nothing scrolls by section here.
                    item("header") { Header(context, money = false) }
                    item("details") { DetailsSection(context) }
                    item("files") { FilesSection(context) }
                    item("payout") { ArtistPayout(context) }
                    item("costs") { ArtistCosts(context, onOpenCost, onAddCost = { onAddCost(eventId) }) }
                    item("lineup") { ArtistLineup(context) }
                    return@LazyColumn
                }
                // In the order of [EventSection]: its ordinal is the item's index, for scrolling to To settle.
                item(EventSection.HEADER) { Header(context) }
                item(EventSection.DETAILS) { DetailsSection(context) }
                item(EventSection.FILES) { FilesSection(context) }
                item(EventSection.PROBLEMS) { Problems(context) }
                item(EventSection.PARTICIPANTS) { ParticipantsSection(context) }
                item(EventSection.COLLECTED) { CollectedSection(context) }
                item(EventSection.REFUNDS) { RefundsSection(context, onAddCost = { onAddCost(eventId) }) }
                item(EventSection.SUMMARY) { SummarySection(context) }
                item(EventSection.SHARE) { shareSettlement?.let { ShareSettlementButton(it) } }
                item(EventSection.TO_SETTLE) { TransfersSection(context) }
                item(EventSection.HISTORY) { HistorySection(context, graph) }
            }
        }
        if (editing) EditDetailsDialog(state.band, event, actions, onDismiss = { editing = false })
    }
}

/** The event screen's sections, in list order: a reminder about an open debt scrolls to [TO_SETTLE] by its ordinal. */
private enum class EventSection { HEADER, DETAILS, FILES, PROBLEMS, PARTICIPANTS, COLLECTED, REFUNDS, SUMMARY, SHARE, TO_SETTLE, HISTORY }

/** One list item per section, keyed by it, so the item's index is always the section's ordinal. */
private fun LazyListScope.item(section: EventSection, content: @Composable () -> Unit) = item(key = section.name) { content() }

@Composable
private fun Header(context: EventContext, money: Boolean = true) {
    val event = context.event
    val s = context.settlement
    var editingFund by remember { mutableStateOf(false) }
    if (editingFund) FundAmountDialog(context, onDismiss = { editingFund = false })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(formatDate(event.date, language), event.location).joinToString(" · "),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (money) StatusChip(s.status, event.archived)
        }
        event.agendaLine()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        // Google Maps: the stored link (the exact pin), otherwise directions to the location text.
        Places.navigateUrl(event)?.let { url ->
            val uriHandler = LocalUriHandler.current
            AssistChip(
                onClick = { uriHandler.openUri(url) },
                label = { Text(stringResource(Res.string.navigate)) },
                leadingIcon = { Icon(Icons.Default.Directions, null) },
            )
        }
        if (money) Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TotalRow(stringResource(Res.string.total_income), s.income, context.currency)
                // Part of the income is a fee the organiser has not paid yet (ITERATION_4 §2.2).
                if (event.awaited.isPositive) TotalRow(stringResource(Res.string.total_awaited), event.awaited, context.currency)
                val canSetFund = !event.archived && context.band.treasurerMemberId != null && context.can.allowed(SetFundAmount(event.id, Money(1)))
                if (!s.fundAmount.isZero || canSetFund) {
                    // Shown as what it does to the amount to share: money to the fund is taken off, money from it is added.
                    TotalRow(
                        stringResource(if (s.fundAmount.isNegative) Res.string.total_from_fund else Res.string.total_fund),
                        -s.fundAmount,
                        context.currency,
                        onEdit = { editingFund = true }.takeIf { canSetFund },
                    )
                }
                if (!s.refundsTotal.isZero) TotalRow(stringResource(Res.string.total_refunds), -s.refundsTotal, context.currency)
                TotalRow(stringResource(Res.string.total_net), s.net, context.currency, bold = true)
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: Money, currency: String, bold: Boolean = false, onEdit: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        MoneyText(amount, currency, bold = bold)
        if (onEdit != null) EditButton(onEdit)
    }
}

/**
 * How much of this event goes to the band fund, or comes from it to top up the payouts. The direction is a choice
 * rather than a minus sign: phone keyboards for amounts often have no minus key.
 */
@Composable
private fun FundAmountDialog(context: EventContext, onDismiss: () -> Unit) {
    val lang = language
    val current = context.event.fundAmount
    var fromFund by remember { mutableStateOf(current.isNegative) }
    var amount by remember { mutableStateOf(if (current.isZero) "" else formatMoneyInput(Money(abs(current.minor)), lang)) }
    val parsed = if (amount.isBlank()) Money.ZERO else parseMoney(amount)?.takeIf { !it.isNegative }
    val signed = parsed?.let { if (fromFund) -it else it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.band_fund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to Res.string.fund_direction_to, true to Res.string.fund_direction_from).forEachIndexed { index, (value, label) ->
                        SegmentedButton(selected = fromFund == value, onClick = { fromFund = value }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                            Text(stringResource(label))
                        }
                    }
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Text(stringResource(if (fromFund) Res.string.fund_amount_from_hint else Res.string.fund_amount_to_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = signed != null, onClick = {
                if (signed == current || context.actions.run(SetFundAmount(context.event.id, signed!!))) onDismiss()
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
                        // Fixed shares count towards the loss too (ITERATION_3 §2.4), so lowering them is a way out.
                        SettlementProblem.LOSS -> stringResource(
                            if (context.settlement.fixedTotal.isZero) Res.string.problem_loss else Res.string.problem_loss_fixed,
                            formatMoney(context.settlement.shortfall, context.currency, language),
                        )
                        SettlementProblem.NO_SHARES -> stringResource(Res.string.problem_no_shares)
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
        context.graph, AttachmentOwner.OfEvent(event.id), event.attachments,
        drafts = emptyList(), onDrafts = {}, editable = editable, actions = context.actions,
        max = MAX_EVENT_ATTACHMENTS, hint = Res.string.attachment_hint_event, title = null,
    )
}
