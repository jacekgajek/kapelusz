package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.back
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.summary_all_time
import com.jacekgajek.kapelusz.app.generated.summary_and_more
import com.jacekgajek.kapelusz.app.generated.summary_band
import com.jacekgajek.kapelusz.app.generated.summary_categories
import com.jacekgajek.kapelusz.app.generated.summary_collected
import com.jacekgajek.kapelusz.app.generated.summary_costs
import com.jacekgajek.kapelusz.app.generated.summary_custom
import com.jacekgajek.kapelusz.app.generated.summary_earned
import com.jacekgajek.kapelusz.app.generated.summary_empty
import com.jacekgajek.kapelusz.app.generated.summary_events
import com.jacekgajek.kapelusz.app.generated.summary_fund_balance
import com.jacekgajek.kapelusz.app.generated.summary_fund_in
import com.jacekgajek.kapelusz.app.generated.summary_fund_out
import com.jacekgajek.kapelusz.app.generated.summary_guests
import com.jacekgajek.kapelusz.app.generated.summary_last_12
import com.jacekgajek.kapelusz.app.generated.summary_last_year
import com.jacekgajek.kapelusz.app.generated.summary_members
import com.jacekgajek.kapelusz.app.generated.summary_paid_out
import com.jacekgajek.kapelusz.app.generated.summary_refunded
import com.jacekgajek.kapelusz.app.generated.summary_this_year
import com.jacekgajek.kapelusz.app.generated.summary_title
import com.jacekgajek.kapelusz.app.generated.summary_unsettled
import com.jacekgajek.kapelusz.app.generated.summary_waiting
import com.jacekgajek.kapelusz.app.generated.summary_you
import com.jacekgajek.kapelusz.app.ui.components.Avatar
import com.jacekgajek.kapelusz.app.ui.components.DateRangeDialog
import com.jacekgajek.kapelusz.app.ui.components.GuestIcon
import com.jacekgajek.kapelusz.app.ui.components.IconCircle
import com.jacekgajek.kapelusz.app.ui.components.InviteMemberDialog
import com.jacekgajek.kapelusz.app.ui.components.NotInAppIcon
import com.jacekgajek.kapelusz.app.ui.components.icon
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.PeriodSummary
import com.jacekgajek.kapelusz.app.ui.model.PersonTotals
import com.jacekgajek.kapelusz.app.ui.model.SummaryPeriod
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.isArtist
import com.jacekgajek.kapelusz.app.ui.model.periodSummary
import com.jacekgajek.kapelusz.app.ui.screens.settings.SectionHeader
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What a period brought (ITERATION_6 §7): you, the band, costs by category, members. Nothing to tap but invites. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(graph: AppGraph, state: BandState.Ready, onBack: () -> Unit, onInviteMember: (String) -> Unit) {
    val band = state.band
    val artist = band.isArtist(state.userId)
    val period = graph.settings.settings.collectAsState().value.summaryPeriod(band.id)
    val today = today()
    val (from, to) = period.range(today)
    // Settling every event is costly: in the background, again when the band changes (the old numbers stay meanwhile);
    // another period starts from the spinner, not the old period's numbers.
    var shown by remember { mutableStateOf<Pair<SummaryPeriod, PeriodSummary>?>(null) }
    LaunchedEffect(band, state.me, period, today) {
        if (shown?.first != period) shown = null
        val me = state.me
        shown = period to withContext(Dispatchers.Default) { periodSummary(band, me, from, to) }
    }
    var choosingRange by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf<Member?>(null) }
    val canInvite = Can(band, state.userId).manageRoster()
    fun choose(period: SummaryPeriod) = graph.settings.update { it.copy(summaryPeriods = it.summaryPeriods + (band.id to period)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.summary_title))
                        Text(band.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).listWidth(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp)) {
            item(key = "periods") {
                val presets = listOf(
                    SummaryPeriod.Last12Months to Res.string.summary_last_12,
                    SummaryPeriod.ThisYear to Res.string.summary_this_year,
                    SummaryPeriod.LastYear to Res.string.summary_last_year,
                    SummaryPeriod.AllTime to Res.string.summary_all_time,
                )
                // Scrolling sideways on a narrow phone rather than wrapping; the chosen one in view (a custom range is last).
                val chips = rememberLazyListState()
                val selected = presets.indexOfFirst { it.first == period }.takeIf { it >= 0 } ?: presets.size
                LaunchedEffect(selected) { chips.animateScrollToItem(selected) }
                LazyRow(state = chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets) { (preset, label) ->
                        FilterChip(selected = period == preset, onClick = { choose(preset) }, label = { Text(stringResource(label)) })
                    }
                    item {
                        val custom = period as? SummaryPeriod.Custom
                        FilterChip(
                            selected = custom != null,
                            onClick = { choosingRange = true },
                            label = {
                                Text(custom?.let { formatDate(it.from, language) + " – " + formatDate(it.to, language) } ?: stringResource(Res.string.summary_custom))
                            },
                        )
                    }
                }
            }
            val summary = shown?.second
            if (summary == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                summaryContent(summary, artist, band.currency, state.me?.id, canInvite) { inviting = it }
            }
        }
    }

    if (choosingRange) {
        val custom = period as? SummaryPeriod.Custom
        DateRangeDialog(custom?.from ?: from, custom?.to ?: to, onRange = { start, end -> choose(SummaryPeriod.Custom(start, end)) }, onDismiss = { choosingRange = false })
    }
    inviting?.let { member ->
        InviteMemberDialog(member.name, onInvite = { onInviteMember(member.id) }, onDismiss = { inviting = null })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.summaryContent(
    summary: PeriodSummary,
    artist: Boolean,
    currency: String,
    myId: String?,
    canInvite: Boolean,
    onInvite: (Member) -> Unit,
) {
    // An artist sees only their own events and money (ITERATION_4 §1.1).
    val unsettled = if (artist) summary.unsettled.filter { event -> myId != null && event.participant(myId) != null } else summary.unsettled
    if (unsettled.isNotEmpty()) item(key = "unsettled") {
        val names = unsettled.take(3).joinToString(", ") { it.name } +
            if (unsettled.size > 3) " " + stringResource(Res.string.summary_and_more, unsettled.size - 3) else ""
        Text(stringResource(Res.string.summary_unsettled, names), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
    val showMe = !summary.me.isZero || summary.myWaiting.isPositive
    val empty = !showMe && (artist || summary.isEmpty)
    if (empty) {
        item(key = "empty") { Text(stringResource(Res.string.summary_empty), Modifier.padding(vertical = 32.dp)) }
        return
    }
    if (showMe) item(key = "you") {
        Column {
            SectionHeader(stringResource(Res.string.summary_you))
            AmountRow(Res.string.summary_earned, summary.me.earned, currency)
            AmountRow(Res.string.summary_refunded, summary.me.refunded, currency)
            AmountRow(Res.string.summary_waiting, summary.myWaiting, currency)
        }
    }
    if (artist) return
    item(key = "band") {
        Column {
            SectionHeader(stringResource(Res.string.summary_band))
            ValueRow(stringResource(Res.string.summary_events), summary.events.toString())
            AmountRow(Res.string.summary_collected, summary.collected, currency)
            AmountRow(Res.string.summary_paid_out, summary.paidOut, currency)
            AmountRow(Res.string.summary_costs, summary.costs, currency)
            summary.fund?.let { fund ->
                SectionHeader(stringResource(Res.string.band_fund))
                AmountRow(Res.string.summary_fund_in, fund.into, currency)
                AmountRow(Res.string.summary_fund_out, fund.out, currency)
                AmountRow(Res.string.summary_fund_balance, fund.balance, currency)
            }
        }
    }
    if (summary.categories.isNotEmpty()) {
        item(key = "categories") { SectionHeader(stringResource(Res.string.summary_categories)) }
        items(summary.categories, key = { "category-" + it.first.name }) { (category, amount) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(category.icon())
                Text(category.label(), Modifier.weight(1f).padding(start = 12.dp))
                Text(formatMoney(amount, currency, language))
            }
        }
    }
    if (summary.members.isNotEmpty() || summary.guests != null) item(key = "members") { SectionHeader(stringResource(Res.string.summary_members)) }
    items(summary.members, key = { "member-" + it.first.id }) { (member, totals) ->
        // As on the roster and in events: a member not in the app is the crossed eye, a tap invites them (only those
        // who may invite; former members need to be back first).
        val invite = canInvite && member.userId == null && !member.archived && member.id != myId
        PersonRow(totals, currency, member.name, Modifier.clickable(enabled = invite, role = Role.Button) { onInvite(member) }) {
            if (member.userId == null) NotInAppIcon(40.dp) else Avatar(member.name, member.pictureUrl, 40.dp, decorative = true)
        }
    }
    summary.guests?.let { guests ->
        item(key = "guests") { PersonRow(guests, currency, stringResource(Res.string.summary_guests)) { GuestIcon(40.dp) } }
    }
}

@Composable
private fun AmountRow(label: StringResource, amount: Money, currency: String) = ValueRow(stringResource(label), formatMoney(amount, currency, language))

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value)
    }
}

/** A person's two lines beside their avatar: the name, then what they earned and got refunded. */
@Composable
private fun PersonRow(totals: PersonTotals, currency: String, name: String, modifier: Modifier = Modifier, avatar: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        avatar()
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.summary_earned) + " " + formatMoney(totals.earned, currency, language) + " · " +
                    stringResource(Res.string.summary_refunded) + " " + formatMoney(totals.refunded, currency, language),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
