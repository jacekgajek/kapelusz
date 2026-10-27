package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.back
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.not_in_app_invite
import com.jacekgajek.kapelusz.app.generated.summary_all_time
import com.jacekgajek.kapelusz.app.generated.summary_and_more
import com.jacekgajek.kapelusz.app.generated.summary_band
import com.jacekgajek.kapelusz.app.generated.summary_categories
import com.jacekgajek.kapelusz.app.generated.summary_collected
import com.jacekgajek.kapelusz.app.generated.summary_custom
import com.jacekgajek.kapelusz.app.generated.summary_earned
import com.jacekgajek.kapelusz.app.generated.summary_empty
import com.jacekgajek.kapelusz.app.generated.summary_events
import com.jacekgajek.kapelusz.app.generated.summary_former_members
import com.jacekgajek.kapelusz.app.generated.summary_fund_balance
import com.jacekgajek.kapelusz.app.generated.summary_fund_in
import com.jacekgajek.kapelusz.app.generated.summary_fund_out
import com.jacekgajek.kapelusz.app.generated.summary_guests
import com.jacekgajek.kapelusz.app.generated.summary_last_12
import com.jacekgajek.kapelusz.app.generated.summary_last_year
import com.jacekgajek.kapelusz.app.generated.summary_members
import com.jacekgajek.kapelusz.app.generated.summary_paid_out
import com.jacekgajek.kapelusz.app.generated.summary_refunded
import com.jacekgajek.kapelusz.app.generated.summary_refunds_at_events
import com.jacekgajek.kapelusz.app.generated.summary_this_year
import com.jacekgajek.kapelusz.app.generated.summary_title
import com.jacekgajek.kapelusz.app.generated.summary_to_fund
import com.jacekgajek.kapelusz.app.generated.summary_total
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
import com.jacekgajek.kapelusz.app.ui.model.summaryPeriod
import com.jacekgajek.kapelusz.app.ui.model.withSummaryPeriod
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
        val me = state.me
        // An artist sees only their own events and money (ITERATION_4 §1.1).
        shown = period to withContext(Dispatchers.Default) { periodSummary(band, me, from, to, onlyEventsOf = me?.id?.takeIf { artist }) }
    }
    var choosingRange by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf<Member?>(null) }
    val canInvite = Can(band, state.userId).manageRoster()
    fun choose(period: SummaryPeriod) = graph.settings.update { it.withSummaryPeriod(band.id, period) }

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
        LazyColumn(Modifier.fillMaxSize().padding(padding).listWidth(), contentPadding = PaddingValues(8.dp, 0.dp, 8.dp, 32.dp)) {
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
                LazyRow(state = chips, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = Inset)) {
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
            val summary = shown?.takeIf { it.first == period }?.second
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
    val unsettled = summary.unsettled
    if (unsettled.isNotEmpty()) item(key = "unsettled") {
        val names = unsettled.take(3).joinToString(", ") { it.name } +
            if (unsettled.size > 3) " " + stringResource(Res.string.summary_and_more, unsettled.size - 3) else ""
        Text(stringResource(Res.string.summary_unsettled, names), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = Inset, end = Inset, top = 12.dp))
    }
    val showMe = !summary.me.isZero || summary.myWaiting.isPositive
    if (showMe) item(key = "you") {
        Column {
            SummaryHeader(stringResource(Res.string.summary_you))
            AmountRow(Res.string.summary_earned, summary.me.earned, currency, 0)
            AmountRow(Res.string.summary_refunded, summary.me.refunded, currency, 1)
            AmountRow(Res.string.summary_waiting, summary.myWaiting, currency, 2)
        }
    }
    // An artist sees only their own money (ITERATION_4 §1.1).
    val empty = if (artist) summary.me.isZero else summary.isEmpty
    if (empty) {
        item(key = "empty") { Text(stringResource(Res.string.summary_empty), Modifier.padding(horizontal = Inset, vertical = 32.dp)) }
        return
    }
    if (artist) return
    item(key = "band") {
        Column {
            SummaryHeader(stringResource(Res.string.summary_band))
            ValueRow(stringResource(Res.string.summary_events), summary.events.toString(), 0)
            // Where the collected money went: the indented lines add up to it.
            AmountRow(Res.string.summary_collected, summary.collected, currency, 1)
            AmountRow(Res.string.summary_paid_out, summary.paidOut, currency, 2, indent = true)
            AmountRow(Res.string.summary_refunds_at_events, summary.refundedAtEvents, currency, 3, indent = true)
            if (summary.fund != null || !summary.toFund.isZero) AmountRow(Res.string.summary_to_fund, summary.toFund, currency, 4, indent = true)
            summary.fund?.let { fund ->
                SummaryHeader(stringResource(Res.string.band_fund))
                AmountRow(Res.string.summary_fund_in, fund.into, currency, 0)
                AmountRow(Res.string.summary_fund_out, fund.out, currency, 1)
                AmountRow(Res.string.summary_fund_balance, fund.balance, currency, 2)
            }
        }
    }
    if (summary.categories.isNotEmpty()) {
        item(key = "categories") {
            Column {
                SummaryHeader(stringResource(Res.string.summary_categories))
                // Fully refunded costs, from events or the fund, on the day they were.
                AmountRow(Res.string.summary_total, summary.costs, currency, 0)
            }
        }
        itemsIndexed(summary.categories, key = { _, it -> "category-" + it.first.name }) { index, (category, amount) ->
            Row(Modifier.stripe(index + 1).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(category.icon())
                Text(category.label(), Modifier.weight(1f).padding(start = 12.dp))
                Text(formatMoney(amount, currency, language))
            }
        }
    }
    val (former, current) = summary.members.partition { it.first.archived }
    // The heading over current and former members alike: "Former members" is part of it.
    if (summary.members.isNotEmpty() || summary.guests != null) item(key = "members") { SummaryHeader(stringResource(Res.string.summary_members)) }
    itemsIndexed(current, key = { _, it -> "member-" + it.first.id }) { index, (member, totals) ->
        // As on the roster and in events: a member not in the app is the crossed eye, a tap invites them (only those
        // who may invite).
        val invite = canInvite && member.userId == null && member.id != myId
        val inviteLabel = stringResource(Res.string.not_in_app_invite)
        PersonRow(totals, currency, member.name, index, if (invite) Modifier.clickable(onClickLabel = inviteLabel, role = Role.Button) { onInvite(member) } else Modifier) {
            MemberAvatar(member)
        }
    }
    summary.guests?.let { guests ->
        item(key = "guests") { PersonRow(guests, currency, stringResource(Res.string.summary_guests), current.size) { GuestIcon(40.dp) } }
    }
    // Who has left the band, apart: nothing to invite them to until they're back.
    if (former.isNotEmpty()) item(key = "former") {
        Text(
            stringResource(Res.string.summary_former_members),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Inset, end = Inset, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    itemsIndexed(former, key = { _, it -> "member-" + it.first.id }) { index, (member, totals) ->
        PersonRow(totals, currency, member.name, index) { MemberAvatar(member) }
    }
}

/** The rows' shading reaches into the screen's margin, by this much; everything else is inset by it, text stays in line. */
private val Inset = 8.dp

@Composable
private fun SummaryHeader(text: String) = SectionHeader(text, Modifier.padding(horizontal = Inset))

/**
 * A row's place in its block, every other one shaded: the band runs from the label to its amount, so the eye doesn't
 * slip to the next line's number across the width of the screen. [inside] goes on the whole band (a tap's ripple).
 */
@Composable
private fun Modifier.stripe(index: Int, inside: Modifier = Modifier): Modifier {
    val shade = if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    return fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(shade).then(inside).padding(horizontal = Inset)
}

@Composable
private fun AmountRow(label: StringResource, amount: Money, currency: String, index: Int, indent: Boolean = false) =
    ValueRow(stringResource(label), formatMoney(amount, currency, language), index, indent)

/** A label and its value, [index] in its block (see [stripe]); [indent]: a part of the row above. */
@Composable
private fun ValueRow(label: String, value: String, index: Int, indent: Boolean = false) {
    Row(Modifier.stripe(index).padding(start = if (indent) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value)
    }
}

/**
 * A person's lines beside their avatar: the name, then what they earned and what they got refunded, one per line;
 * [index] among the people (see [stripe]).
 */
@Composable
private fun PersonRow(totals: PersonTotals, currency: String, name: String, index: Int, modifier: Modifier = Modifier, avatar: @Composable () -> Unit) {
    // One stop for screen readers: the name with both amounts, not five pieces (a tap to invite merges anyway).
    Row(
        Modifier.stripe(index, modifier).semantics(mergeDescendants = true) {}.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PersonAmount(Res.string.summary_earned, totals.earned, currency)
            PersonAmount(Res.string.summary_refunded, totals.refunded, currency)
        }
    }
}

@Composable
private fun PersonAmount(label: StringResource, amount: Money, currency: String) =
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatMoney(amount, currency, language), style = MaterialTheme.typography.bodyMedium)
    }

@Composable
private fun MemberAvatar(member: Member) =
    if (member.userId == null) NotInAppIcon(40.dp) else Avatar(member.name, member.pictureUrl, 40.dp, decorative = true)
