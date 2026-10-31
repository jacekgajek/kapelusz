package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.cost_date
import com.jacekgajek.kapelusz.app.generated.delete
import com.jacekgajek.kapelusz.app.generated.description
import com.jacekgajek.kapelusz.app.generated.fund_add_cost
import com.jacekgajek.kapelusz.app.generated.fund_add_entry
import com.jacekgajek.kapelusz.app.generated.fund_balance
import com.jacekgajek.kapelusz.app.generated.fund_choose_cost
import com.jacekgajek.kapelusz.app.generated.fund_each
import com.jacekgajek.kapelusz.app.generated.fund_entry_from
import com.jacekgajek.kapelusz.app.generated.fund_entry_outside
import com.jacekgajek.kapelusz.app.generated.fund_entry_refund_to
import com.jacekgajek.kapelusz.app.generated.fund_handed_over
import com.jacekgajek.kapelusz.app.generated.fund_held_by
import com.jacekgajek.kapelusz.app.generated.fund_history
import com.jacekgajek.kapelusz.app.generated.fund_history_empty
import com.jacekgajek.kapelusz.app.generated.fund_in
import com.jacekgajek.kapelusz.app.generated.fund_newest_first
import com.jacekgajek.kapelusz.app.generated.fund_no_costs
import com.jacekgajek.kapelusz.app.generated.fund_no_treasurer
import com.jacekgajek.kapelusz.app.generated.fund_no_treasurer_admin
import com.jacekgajek.kapelusz.app.generated.fund_not_handed_over
import com.jacekgajek.kapelusz.app.generated.fund_oldest_first
import com.jacekgajek.kapelusz.app.generated.fund_out
import com.jacekgajek.kapelusz.app.generated.fund_outside
import com.jacekgajek.kapelusz.app.generated.fund_paid_by
import com.jacekgajek.kapelusz.app.generated.fund_people
import com.jacekgajek.kapelusz.app.generated.fund_refund_cost
import com.jacekgajek.kapelusz.app.generated.fund_select_all
import com.jacekgajek.kapelusz.app.generated.fund_several
import com.jacekgajek.kapelusz.app.generated.fund_waiting
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.open_settings
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.app.generated.tab_fund
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.app.ui.BandSession
import com.jacekgajek.kapelusz.app.ui.FundNav
import com.jacekgajek.kapelusz.app.ui.components.Actions
import com.jacekgajek.kapelusz.app.ui.components.AmountRow
import com.jacekgajek.kapelusz.app.ui.components.AppScaffold
import com.jacekgajek.kapelusz.app.ui.components.Choice
import com.jacekgajek.kapelusz.app.ui.components.DateField
import com.jacekgajek.kapelusz.app.ui.components.DialogNotice
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import com.jacekgajek.kapelusz.app.ui.components.EditButton
import com.jacekgajek.kapelusz.app.ui.components.MoneyField
import com.jacekgajek.kapelusz.app.ui.components.MoneyText
import com.jacekgajek.kapelusz.app.ui.components.PaymentKind
import com.jacekgajek.kapelusz.app.ui.components.PaymentRow
import com.jacekgajek.kapelusz.app.ui.components.SectionTitle
import com.jacekgajek.kapelusz.app.ui.components.SegmentedChoice
import com.jacekgajek.kapelusz.app.ui.components.TabTopBar
import com.jacekgajek.kapelusz.app.ui.components.label
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.components.listWidth
import com.jacekgajek.kapelusz.app.ui.components.rememberActions
import com.jacekgajek.kapelusz.app.ui.components.rememberMessages
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatMoneyInput
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.FundEntry
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.ops.AddFundEntry
import com.jacekgajek.kapelusz.ops.UpdateFundEntry
import com.jacekgajek.kapelusz.settlement.BandFund
import com.jacekgajek.kapelusz.settlement.CostLedger
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/** The band fund ("Kasa zespołowa"): balance, contributions from events, and manual entries. */
@Composable
fun BandFundScreen(
    session: BandSession,
    nav: FundNav,
    /** A cost just added from here (the + beside the costs to refund), to refund (ITERATION_7 §5.1); [onAddedCostSeen] once its dialog is open. */
    addedCost: String? = null,
    onAddedCostSeen: () -> Unit = {},
    entries: FundEntryViewModel = viewModel(),
) {
    val state = session.state
    val changes = session.changes
    val preferences = session.settings
    val onOpenEvent: (String) -> Unit = nav::openEvent
    val onAddCost: () -> Unit = nav::addCost
    val onSettings: () -> Unit = nav::settings
    val band = state.band
    val messages = rememberMessages()
    val actions = rememberActions(changes, messages, undos = null)
    val can = session.can
    val canEdit = band.treasurerMemberId != null && can.allowed(AddFundEntry("probe", today(), "probe", Money(1)))
    // Leads only: refunding a cost decides about it (ITERATION_7 §3.3); the treasurer records money paid in.
    val refundsCosts = can.allowed(AddFundEntry("probe", today(), "probe", Money(-1), costId = "probe"))
    val settings by preferences.settings.collectAsState()
    val lang = language
    // What a refund is called when the cost has no description: its category, else "Cost" (as in the cost lists).
    val categoryLabels = CostCategory.entries.associateWith { it.label() }
    val unnamed = stringResource(Res.string.unnamed_cost)
    val costName = { cost: Cost -> cost.description ?: cost.category?.let(categoryLabels::getValue) ?: unnamed }
    val amountText = { money: Money -> formatMoneyInput(money, lang) }
    val me = state.me?.id
    // Back from adding a cost with the +: its refund, in a new entry.
    LaunchedEffect(addedCost) {
        if (addedCost != null) {
            entries.refundAddedCost(band, addedCost, me, refundsCosts, today(), amountText, costName)
            onAddedCostSeen()
        }
    }

    AppScaffold(
        messages,
        topBar = { TabTopBar(stringResource(Res.string.tab_fund)) },
        floatingActionButton = {
            if (canEdit) {
                ExtendedFloatingActionButton(
                    onClick = { entries.add(band, me, refundsCosts, today(), amountText, costName) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(Res.string.fund_add_entry)) },
                )
            }
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
            return@AppScaffold
        }
        // One list, by date (ITERATION_7 §5.1): events' money first on a day, entries in the order they were added.
        val rows = remember(band, settings.fundOldestFirst) {
            val all = band.events.filter { !it.fund.isZero }.map { FundRow.OfEvent(it) } +
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
                        AmountRow(
                            stringResource(Res.string.fund_balance),
                            BandFund.balance(band),
                            bold = true,
                            colored = true,
                            labelStyle = MaterialTheme.typography.titleMedium,
                        )
                        val waiting = BandFund.notHandedOver(band)
                        if (!waiting.isZero) {
                            AmountRow(stringResource(Res.string.fund_not_handed_over), waiting, labelStyle = LocalTextStyle.current)
                        }
                        Text(stringResource(Res.string.fund_held_by, treasurer.name), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            // Where to pay in, for everyone, once set (ITERATION_7 §7): only shown here, set in the fund's settings.
            if (band.fundBlikPhone != null || band.fundBankAccount != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp, 8.dp)) {
                            val fund = stringResource(Res.string.band_fund)
                            PaymentRow(PaymentKind.BLIK, null, band.fundBlikPhone, fund)
                            PaymentRow(PaymentKind.ACCOUNT, null, band.fundBankAccount, fund)
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.fund_history)) }
                    val order = stringResource(if (settings.fundOldestFirst) Res.string.fund_oldest_first else Res.string.fund_newest_first)
                    TextButton(onClick = { preferences.update { it.copy(fundOldestFirst = !it.fundOldestFirst) } }) {
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
                            MoneyText(event.fund, colored = true)
                        }
                    }

                    is FundRow.OfEntry -> {
                        val entry = row.entry
                        val editable = canEdit && can.allowed(UpdateFundEntry(entry.id))
                        val edit = { entries.edit(band, entry, me, today(), amountText, costName) }
                        Row(Modifier.fillMaxWidth().clickable(enabled = editable, onClick = edit).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            MoneyText(entry.amount, colored = true)
                            if (editable) EditButton(onClick = edit)
                        }
                    }
                }
            }
        }
    }

    entries.form?.let { form ->
        FundEntryDialog(
            form,
            band,
            onAddCost = {
                entries.dismiss()
                onAddCost()
            }.takeIf { form.isNew },
            onSave = { actions.show(entries.save { changes.dispatch(it) }) },
            onDelete = if (form.isNew) {
                null
            } else {
                { actions.show(entries.delete { changes.dispatch(it) }) }
            },
            onDismiss = entries::dismiss,
            actions = actions,
        )
    }
}

/** An outlined field's label sits above its box: a button beside it moves down by as much, to the box's middle. */
private val FIELD_LABEL_INSET = 8.dp

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

/** The entry dialog over a [FundEntryForm]: fields, the people and cost pickers, Save, Delete for an existing entry. */
@Composable
private fun FundEntryDialog(
    form: FundEntryForm,
    band: Band,
    onAddCost: (() -> Unit)?,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** Whose refusals show in the dialog ([DialogNotice]). */
    actions: Actions,
) {
    val lang = language
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.band_fund)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A new entry's direction; the treasurer (not a lead) only records money paid in.
                if (form.isNew && form.refundsCosts) {
                    val directions = listOf(
                        Choice(true, stringResource(Res.string.fund_in)),
                        Choice(false, stringResource(Res.string.fund_out)),
                    )
                    SegmentedChoice(directions, form.incoming, { form.incoming = it })
                }
                if (form.incoming) {
                    PayersField(form, band)
                } else if (form.isNew) {
                    CostField(form, band, onAddCost)
                }
                MoneyField(form.amount, { form.amount = it }, stringResource(Res.string.amount))
                val parsed = form.parsed
                if (form.incoming && form.payers.size > 1 && parsed != null) {
                    val total = formatMoney(Money(parsed.minor * form.payers.size), band.currency, lang)
                    Text(stringResource(Res.string.fund_each, form.payers.size, total), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    form.description,
                    { form.description = it },
                    label = { Text(stringResource(Res.string.description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(form.date, { form.date = it }, stringResource(Res.string.cost_date))
                DialogNotice(actions)
            }
        },
        confirmButton = { TextButton(enabled = form.ready, onClick = onSave) { Text(stringResource(Res.string.save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
    if (form.choosingPeople) {
        PeopleDialog(
            people = form.people,
            initial = form.payers.filterNotNull().toSet(),
            onDone = form::choosePeople,
            onDismiss = { form.choosingPeople = false },
        )
    }
}

/** Who paid in: a member, or outside the band; for a new entry, several people at once (one entry each). */
@Composable
private fun PayersField(form: FundEntryForm, band: Band) {
    val outside = stringResource(Res.string.fund_outside)
    fun name(id: String?) = if (id == null) outside else band.member(id)?.name.orEmpty()
    val payers = form.payers
    val options = (form.people.map { listOf<String?>(it.id) } + listOf(listOf<String?>(null)))
        .let { if (payers.size == 1 && payers !in it) listOf(payers) + it else it }
    val several = stringResource(Res.string.fund_people, payers.size)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dropdown(payers, options, { form.payers = it }, stringResource(Res.string.fund_paid_by), Modifier.weight(1f)) { chosen ->
            if (chosen.size > 2) several else chosen.joinToString(", ") { name(it) }
        }
        // Several people paying the same, e.g. this month's fee: one entry each.
        if (form.isNew) {
            IconButton(onClick = { form.choosingPeople = true }, Modifier.padding(top = FIELD_LABEL_INSET)) {
                Icon(Icons.Default.PeopleAlt, stringResource(Res.string.fund_several))
            }
        }
    }
}

/** The cost a refund is for, among those with something left to refund; [onAddCost] adds one first. */
@Composable
private fun CostField(form: FundEntryForm, band: Band, onAddCost: (() -> Unit)?) {
    val lang = language
    val choose = stringResource(Res.string.fund_choose_cost)
    val label = stringResource(Res.string.fund_refund_cost)
    fun describe(cost: Cost): String {
        val outstanding = formatMoney(CostLedger.outstanding(band, cost), band.currency, lang)
        return "${band.member(cost.memberId)?.name.orEmpty()}: ${form.costName(cost)} · $outstanding"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dropdown(form.costId, form.pendingCosts.map { it.id }, { form.pickCost(band, it) }, label, Modifier.weight(1f)) { id ->
                id?.let { band.cost(it) }?.let(::describe) ?: choose
            }
            if (onAddCost != null) {
                IconButton(onClick = onAddCost, Modifier.padding(top = FIELD_LABEL_INSET)) {
                    Icon(Icons.Default.Add, stringResource(Res.string.fund_add_cost))
                }
            }
        }
        if (form.pendingCosts.isEmpty()) Text(stringResource(Res.string.fund_no_costs), style = MaterialTheme.typography.bodySmall)
    }
}

/** Ticking who paid, e.g. everyone's monthly fee (ITERATION_7 §5.1). */
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
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(member.id in chosen, role = SemanticsRole.Checkbox) { on -> chosen = if (on) chosen + member.id else chosen - member.id },
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
