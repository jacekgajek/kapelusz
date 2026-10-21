package com.jacekgajek.artsplit.app.ui.screens.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.jacekgajek.artsplit.app.data.newId
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.add_money
import com.jacekgajek.artsplit.app.generated.amount
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.collected_empty
import com.jacekgajek.artsplit.app.generated.collected_money
import com.jacekgajek.artsplit.app.generated.collected_by
import com.jacekgajek.artsplit.app.generated.delete
import com.jacekgajek.artsplit.app.generated.note_optional
import com.jacekgajek.artsplit.app.generated.save
import com.jacekgajek.artsplit.app.generated.source
import com.jacekgajek.artsplit.app.ui.components.Dropdown
import com.jacekgajek.artsplit.app.ui.components.MoneyField
import com.jacekgajek.artsplit.app.ui.components.MoneyText
import com.jacekgajek.artsplit.app.ui.components.SectionTitle
import com.jacekgajek.artsplit.app.ui.components.label
import com.jacekgajek.artsplit.app.ui.components.language
import com.jacekgajek.artsplit.app.ui.components.parseMoney
import com.jacekgajek.artsplit.app.ui.model.formatMoneyInput
import com.jacekgajek.artsplit.app.ui.model.partyName
import com.jacekgajek.artsplit.model.Holding
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.ops.AddHolding
import com.jacekgajek.artsplit.ops.Change
import com.jacekgajek.artsplit.ops.RemoveHolding
import com.jacekgajek.artsplit.ops.UpdateHolding
import org.jetbrains.compose.resources.stringResource

/** "Where the money sits": who collected how much, from which source. */
@Composable
fun CollectedSection(context: EventContext) {
    val event = context.event
    var editing by remember { mutableStateOf<Holding?>(null) }
    var adding by remember { mutableStateOf(false) }

    SectionTitle(stringResource(Res.string.collected_money))
    if (event.holdings.isEmpty()) Text(stringResource(Res.string.collected_empty), style = MaterialTheme.typography.bodyMedium)
    event.holdings.forEach { holding ->
        Row(
            Modifier.fillMaxWidth().clickable { editing = holding }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(partyName(context.band, event, holding.partyId, ""))
                Text(
                    listOfNotNull(holding.source.label(), holding.note).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            MoneyText(holding.amount, context.currency)
        }
    }
    TextButton(onClick = { adding = true }, enabled = event.participants.isNotEmpty()) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(Res.string.add_money), Modifier.padding(start = 8.dp))
    }

    if (adding) {
        val defaultParty = context.me?.id?.takeIf { event.participant(it) != null } ?: event.participants.first().partyId
        HoldingDialog(context, initial = null, defaultParty, onDismiss = { adding = false })
    }
    editing?.let { holding -> HoldingDialog(context, holding, holding.partyId, onDismiss = { editing = null }) }
}

@Composable
private fun HoldingDialog(context: EventContext, initial: Holding?, defaultParty: String, onDismiss: () -> Unit) {
    val event = context.event
    val lang = language
    var partyId by remember { mutableStateOf(defaultParty) }
    var amount by remember { mutableStateOf(initial?.let { formatMoneyInput(it.amount, lang) } ?: "") }
    var source by remember { mutableStateOf(initial?.source ?: IncomeSource.DOOR) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.collected_money)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    Dropdown(partyId, event.participants.map { it.partyId }, { partyId = it }, stringResource(Res.string.collected_by)) {
                        partyName(context.band, event, it, "")
                    }
                }
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Dropdown(source, IncomeSource.entries, { source = it }, stringResource(Res.string.source)) { it.label() }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(Res.string.note_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                val ok = if (initial == null) {
                    context.actions.run(AddHolding(event.id, newId(), partyId, parsed!!, source, note.ifBlank { null }))
                } else {
                    context.actions.run(
                        UpdateHolding(
                            event.id,
                            initial.id,
                            amount = Change(parsed!!).takeIf { parsed != initial.amount },
                            source = Change(source).takeIf { source != initial.source },
                            note = Change(note.ifBlank { null }).takeIf { note.trim() != initial.note.orEmpty() },
                        ),
                    )
                }
                if (ok) onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { if (context.actions.run(RemoveHolding(event.id, initial.id))) onDismiss() }) {
                        Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
