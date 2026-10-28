package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.ui.components.listWidth
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role as SemanticsRole
import com.jacekgajek.kapelusz.model.Role
import kotlinx.datetime.LocalDate
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Cost
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.fund_no_costs
import com.jacekgajek.kapelusz.app.generated.fund_choose_cost
import com.jacekgajek.kapelusz.app.generated.fund_add_cost
import com.jacekgajek.kapelusz.app.generated.fund_each
import com.jacekgajek.kapelusz.app.generated.fund_people
import com.jacekgajek.kapelusz.app.generated.fund_select_all
import com.jacekgajek.kapelusz.app.generated.fund_several
import com.jacekgajek.kapelusz.app.generated.fund_outside
import com.jacekgajek.kapelusz.app.generated.fund_paid_by
import com.jacekgajek.kapelusz.app.generated.fund_entry_outside
import com.jacekgajek.kapelusz.app.generated.fund_entry_from
import com.jacekgajek.kapelusz.app.generated.fund_oldest_first
import com.jacekgajek.kapelusz.app.generated.fund_newest_first
import com.jacekgajek.kapelusz.app.generated.fund_history_empty
import com.jacekgajek.kapelusz.app.generated.fund_history
import com.jacekgajek.kapelusz.app.generated.fund_entry_refund_to
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.cost_date
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.description
import com.jacekgajek.kapelusz.app.generated.fund_add_entry
import com.jacekgajek.kapelusz.app.generated.fund_balance
import com.jacekgajek.kapelusz.app.generated.fund_held_by
import com.jacekgajek.kapelusz.app.generated.fund_in
import com.jacekgajek.kapelusz.app.generated.fund_no_treasurer
import com.jacekgajek.kapelusz.app.generated.fund_no_treasurer_admin
import com.jacekgajek.kapelusz.app.generated.fund_not_handed_over
import com.jacekgajek.kapelusz.app.generated.fund_out
import com.jacekgajek.kapelusz.app.generated.fund_refund_cost
import com.jacekgajek.kapelusz.app.generated.fund_handed_over
import com.jacekgajek.kapelusz.app.generated.fund_waiting
import com.jacekgajek.kapelusz.app.generated.open_settings
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.domain.BandFund
import com.jacekgajek.kapelusz.domain.CostLedger
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.sumOfMoney
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RemoveFundEntry
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

/** The band fund ("Kasa zespołowa"): balance, contributions from events, and manual entries. */
@Composable
fun BandFundScreen(
    graph: AppGraph,
    state: BandState.Ready,
    onSettings: () -> Unit,
    onRoster: () -> Unit,
    onOpenEvent: (String) -> Unit,
    /** The + beside the costs to refund: a new cost, which comes back as [addedCost]. */
    onAddCost: () -> Unit,
    /** A cost just added from here, to refund (ITERATION_7 §2e); [onAddedCostSeen] once its dialog is open. */
    addedCost: String? = null,
    onAddedCostSeen: () -> Unit = {},
) {
    val band = state.band
    val snackbar = remember { SnackbarHostState() }
    val actions = rememberActions(graph, snackbar)
    val can = Can(band, state.userId)
    val canEdit = band.treasurerMemberId != null && can.allowed(AddFundEntry("probe", today(), "probe", Money(1)))
    var editing by remember { mutableStateOf<FundEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Back from adding a cost with the +: its refund, in a new entry.
    var refunding by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(addedCost) {
        if (addedCost != null) {
            // Not when there is nothing left to refund, e.g. a lead's cost for an event, refunded there at once.
            if (band.cost(addedCost)?.let { CostLedger.outstanding(band, it).isPositive } == true) {
                refunding = addedCost
                adding = true
            }
            onAddedCostSeen()
        }
    }
    val settings by graph.settings.settings.collectAsState()
    val lang = language

    Scaffold(
        topBar = { TabTopBar(stringResource(Res.string.tab_fund), onSettings, onRoster) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canEdit) ExtendedFloatingActionButton(
                onClick = {
                    refunding = null
                    adding = true
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(Res.string.fund_add_entry)) },
            )
        },
    ) { padding ->
        val treasurer = band.treasurerMemberId?.let { band.member(it) }
        if (treasurer == null) {
            Column(Modifier.fillMaxSize().padding(padding).listWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.fund_no_treasurer))
                if (can.manageRoster()) {
                    Text(stringResource(Res.string.fund_no_treasurer_admin), style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = onSettings) { Text(stringResource(Res.string.open_settings)) }
                }
            }
            return@Scaffold
        }
        // One list, by date (ITERATION_7 §2e): events' money first on a day, entries in the order they were added.
        val rows = remember(band, settings.fundOldestFirst) {
            val all = band.events.filter { !it.fundAmount.isZero }.map { FundRow.OfEvent(it) } +
                band.fundEntries.map { FundRow.OfEntry(it) }
            val byDate = if (settings.fundOldestFirst) compareBy<FundRow> { it.date } else compareByDescending { it.date }
            // Stable: entries on a day stay in the order they were added.
            all.sortedWith(byDate.thenBy { if (it is FundRow.OfEvent) 0 else 1 })
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).listWidth(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(stringResource(Res.string.fund_balance), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            MoneyText(BandFund.balance(band), band.currency, colored = true, bold = true)
                        }
                        val waiting = BandFund.notHandedOver(band)
                        if (!waiting.isZero) {
                            Row {
                                Text(stringResource(Res.string.fund_not_handed_over), Modifier.weight(1f))
                                MoneyText(waiting, band.currency)
                            }
                        }
                        Text(stringResource(Res.string.fund_held_by, treasurer.name), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.fund_history)) }
                    val order = stringResource(if (settings.fundOldestFirst) Res.string.fund_oldest_first else Res.string.fund_newest_first)
                    TextButton(onClick = { graph.settings.update { it.copy(fundOldestFirst = !it.fundOldestFirst) } }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.padding(end = 4.dp))
                        Text(order)
                    }
                }
            }
            if (rows.isEmpty()) item { Text(stringResource(Res.string.fund_history_empty), style = MaterialTheme.typography.bodyMedium) }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is FundRow.OfEvent -> {
                        val event = row.event
                        // Counts a fee paid straight to the fund too (ITERATION_4 §2.2).
                        val handedOver = BandFund.isHandedOver(event)
                        Row(Modifier.fillMaxWidth().clickable { onOpenEvent(event.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(event.name)
                                Text(
                                    "${formatDate(event.date, lang)} · " + stringResource(if (handedOver) Res.string.fund_handed_over else Res.string.fund_waiting),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            MoneyText(event.fundAmount, band.currency, colored = true)
                        }
                    }
                    is FundRow.OfEntry -> {
                        val entry = row.entry
                        val editable = canEdit && can.allowed(UpdateFundEntry(entry.id))
                        Row(Modifier.fillMaxWidth().clickable(enabled = editable) { editing = entry }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.description)
                                // Who paid it in, or whose cost it paid back: the description alone ("Cable") doesn't say.
                                val refundOf = entry.costId?.let { band.cost(it) }?.let { band.member(it.memberId)?.name }
                                val who = when {
                                    refundOf != null -> stringResource(Res.string.fund_entry_refund_to, refundOf)
                                    !entry.amount.isPositive -> null
                                    entry.paidBy != null -> stringResource(Res.string.fund_entry_from, band.member(entry.paidBy!!)?.name.orEmpty())
                                    else -> stringResource(Res.string.fund_entry_outside)
                                }
                                Text(listOfNotNull(formatDate(entry.date, lang), who).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            MoneyText(entry.amount, band.currency, colored = true)
                            if (editable) EditButton { editing = entry }
                        }
                    }
                }
            }
        }
    }

    // Several people's contributions are added one by one: each its own entry, Undo and History line.
    val save = { ops: List<Operation> -> ops.all { actions.run(it) } }
    if (adding) FundEntryDialog(
        band, null, state.me?.id,
        refundsCosts = can.allowed(AddFundEntry("probe", today(), "probe", Money(-1), costId = "probe")),
        refund = refunding,
        onAddCost = {
            adding = false
            refunding = null
            onAddCost()
        },
        onSave = save, onDelete = null,
        onDismiss = {
            adding = false
            refunding = null
        },
    )
    editing?.let { entry ->
        FundEntryDialog(band, entry, state.me?.id, onSave = save, onDelete = { actions.run(RemoveFundEntry(entry.id)) }, onDismiss = { editing = null })
    }
}

/** A row of the band fund's list: an event's money for the fund, or an entry. */
private sealed interface FundRow {
    val date: LocalDate
    val key: String

    data class OfEvent(val event: Event) : FundRow {
        override val date get() = event.date
        override val key get() = "event-${event.id}"
    }

    data class OfEntry(val entry: FundEntry) : FundRow {
        override val date get() = entry.date
        override val key get() = "entry-${entry.id}"
    }
}

/**
 * Add or edit an entry (ITERATION_7 §2e): money paid in by someone (a member, several at once, or from outside the band),
 * or the refund of a cost; money leaves the fund only for one. [refund]: a cost to refund, just added with the +.
 */
@Composable
private fun FundEntryDialog(
    band: Band,
    initial: FundEntry?,
    me: String?,
    /** Leads only: refunding a cost decides about it (ITERATION_7 §2d); the treasurer records money paid in. */
    refundsCosts: Boolean = true,
    refund: String? = null,
    onAddCost: (() -> Unit)? = null,
    onSave: (List<Operation>) -> Boolean,
    onDelete: (() -> Boolean)?,
    onDismiss: () -> Unit,
) {
    val lang = language
    // What a refund is called when the cost has no description: its category, else "Cost" (as in the cost lists).
    val categoryLabels = CostCategory.entries.associateWith { it.label() }
    val unnamed = stringResource(Res.string.unnamed_cost)
    fun costName(cost: Cost) = cost.description ?: cost.category?.let(categoryLabels::getValue) ?: unnamed
    var incoming by remember { mutableStateOf(initial?.amount?.isPositive ?: (refund == null)) }
    val refundCost = refund?.let { band.cost(it) }
    var amount by remember {
        mutableStateOf(
            initial?.let { formatMoneyInput(Money(abs(it.amount.minor)), lang) }
                ?: refundCost?.let { formatMoneyInput(CostLedger.outstanding(band, it), lang) } ?: "",
        )
    }
    var description by remember { mutableStateOf(initial?.description ?: refundCost?.let(::costName).orEmpty()) }
    var date by remember { mutableStateOf(initial?.date ?: today()) }
    // Refunding a cost is only offered for new entries; the link cannot be changed later.
    val pendingCosts = band.costs.filter { CostLedger.outstanding(band, it).isPositive }
    var costId by remember { mutableStateOf(initial?.costId ?: refund) }
    // The description filled in from the picked cost: replaced when another cost is picked, unless the user typed over it.
    var filled by remember { mutableStateOf(refundCost?.let(::costName)) }
    // Who paid it in: members, or null for outside the band. The one adding it by default.
    val people = band.members.filter { !it.archived && it.role != Role.VIEWER }
    var payers by remember {
        mutableStateOf(if (initial != null) listOf(initial.paidBy) else listOf(me?.takeIf { id -> people.any { it.id == id } }))
    }
    var choosingPeople by remember { mutableStateOf(false) }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }
    val signed = parsed?.let { if (incoming) it else -it }
    val ready = signed != null && description.isNotBlank() && (incoming || costId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.band_fund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A new entry's direction; the treasurer (not a lead) only records money paid in.
                if (initial == null && refundsCosts) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(true to Res.string.fund_in, false to Res.string.fund_out).forEachIndexed { index, (value, label) ->
                            SegmentedButton(selected = incoming == value, onClick = { incoming = value }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
                if (incoming) {
                    val outside = stringResource(Res.string.fund_outside)
                    fun name(id: String?) = if (id == null) outside else band.member(id)?.name.orEmpty()
                    val options = (people.map { listOf<String?>(it.id) } + listOf(listOf<String?>(null)))
                        .let { if (payers.size == 1 && payers !in it) listOf(payers) + it else it }
                    val several = stringResource(Res.string.fund_people, payers.size)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dropdown(payers, options, { payers = it }, stringResource(Res.string.fund_paid_by), Modifier.weight(1f)) { chosen ->
                            if (chosen.size > 2) several else chosen.joinToString(", ") { name(it) }
                        }
                        // Several people paying the same, e.g. this month's fee: one entry each.
                        if (initial == null) IconButton(onClick = { choosingPeople = true }) {
                            Icon(Icons.Default.PeopleAlt, stringResource(Res.string.fund_several))
                        }
                    }
                } else if (initial == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dropdown(costId, pendingCosts.map { it.id }, { id ->
                            costId = id
                            val cost = band.cost(id!!) ?: return@Dropdown
                            amount = formatMoneyInput(CostLedger.outstanding(band, cost), lang)
                            if (description.isBlank() || description == filled) {
                                description = costName(cost)
                                filled = description
                            }
                        }, stringResource(Res.string.fund_refund_cost), Modifier.weight(1f)) { id ->
                            id?.let { band.cost(it) }?.let { cost ->
                                "${band.member(cost.memberId)?.name.orEmpty()}: ${costName(cost)} · " + formatMoney(CostLedger.outstanding(band, cost), band.currency, lang)
                            } ?: stringResource(Res.string.fund_choose_cost)
                        }
                        if (onAddCost != null) IconButton(onClick = onAddCost) { Icon(Icons.Default.Add, stringResource(Res.string.fund_add_cost)) }
                    }
                    if (pendingCosts.isEmpty()) Text(stringResource(Res.string.fund_no_costs), style = MaterialTheme.typography.bodySmall)
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                if (incoming && payers.size > 1 && parsed != null) {
                    Text(
                        stringResource(Res.string.fund_each, payers.size, formatMoney(Money(parsed.minor * payers.size), band.currency, lang)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(Res.string.description)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(Res.string.cost_date))
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                val ok = when {
                    initial == null && incoming -> {
                        // One by one: on a refusal, who was saved already is no longer ticked (no doubles on a retry).
                        val left = payers.dropWhile { payer -> onSave(listOf(AddFundEntry(newId(), date, description.trim(), signed!!, paidBy = payer))) }
                        if (left.isNotEmpty()) payers = left
                        left.isEmpty()
                    }
                    initial == null -> onSave(listOf(AddFundEntry(newId(), date, description.trim(), signed!!, costId)))
                    else -> onSave(
                        listOf(
                            UpdateFundEntry(
                                initial.id,
                                date = Change(date).takeIf { date != initial.date },
                                description = Change(description.trim()).takeIf { description.trim() != initial.description },
                                amount = Change(signed!!).takeIf { signed != initial.amount },
                                paidBy = Change(payers.single()).takeIf { incoming && payers.single() != initial.paidBy },
                            ),
                        ),
                    )
                }
                if (ok) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { if (onDelete()) onDismiss() }) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
    if (choosingPeople) {
        PeopleDialog(people, payers.filterNotNull().toSet(), onDone = { chosen -> if (chosen.isNotEmpty()) payers = people.map { it.id }.filter { it in chosen } }, onDismiss = { choosingPeople = false })
    }
}

/** Ticking who paid, e.g. everyone's monthly fee (ITERATION_7 §2e). */
@Composable
private fun PeopleDialog(people: List<Member>, initial: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.fund_paid_by)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { chosen = people.map { it.id }.toSet() }) { Text(stringResource(Res.string.fund_select_all)) }
                people.forEach { member ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(member.id in chosen, role = SemanticsRole.Checkbox) { on -> chosen = if (on) chosen + member.id else chosen - member.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(member.id in chosen, onCheckedChange = null)
                        Text(member.name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                onDone(chosen)
                onDismiss()
            }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
