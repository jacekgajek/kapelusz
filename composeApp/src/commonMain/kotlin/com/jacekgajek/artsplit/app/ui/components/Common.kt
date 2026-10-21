package com.jacekgajek.artsplit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jacekgajek.artsplit.app.AppConfig
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Surface
import com.jacekgajek.artsplit.app.generated.transfer_a11y
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.amount
import com.jacekgajek.artsplit.app.generated.amount_invalid
import com.jacekgajek.artsplit.app.generated.cancel
import com.jacekgajek.artsplit.app.generated.edit
import com.jacekgajek.artsplit.app.generated.ok
import com.jacekgajek.artsplit.app.generated.remove
import com.jacekgajek.artsplit.app.generated.settle
import com.jacekgajek.artsplit.app.generated.settle_part_hint
import com.jacekgajek.artsplit.app.generated.settle_part_title
import com.jacekgajek.artsplit.app.generated.status_archived
import com.jacekgajek.artsplit.app.generated.status_draft
import com.jacekgajek.artsplit.app.generated.status_loss
import com.jacekgajek.artsplit.app.generated.status_open
import com.jacekgajek.artsplit.app.generated.status_settled
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.formatMoneyInput
import com.jacekgajek.artsplit.app.ui.model.initials
import com.jacekgajek.artsplit.app.ui.theme.MoneyColors
import com.jacekgajek.artsplit.domain.EventStatus
import com.jacekgajek.artsplit.model.Money
import com.jacekgajek.artsplit.ops.Operation
import com.jacekgajek.artsplit.ops.Rejection
import com.jacekgajek.artsplit.ops.RejectionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Language code of the current UI locale (`en`, `pl`), for formatting. */
val language: String @Composable get() = Locale.current.language

/** Applies operations; a rejection is shown in the snackbar. */
class Actions(
    private val graph: AppGraph,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val reasonTexts: Map<RejectionReason, String>,
) {
    fun run(op: Operation): Boolean = show(graph.dispatch(op))

    /** Shows [rejection] (if any) in the snackbar; true when there was none. */
    fun show(rejection: Rejection?): Boolean {
        if (rejection == null) return true
        say(reasonTexts.getValue(rejection.reason))
        return false
    }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
}

@Composable
fun rememberActions(graph: AppGraph, snackbar: SnackbarHostState): Actions {
    val scope = rememberCoroutineScope()
    // Resolved during composition so they follow the in-app language; a non-composable getString() would use the
    // system language on the web.
    val reasonTexts = RejectionReason.entries.associateWith { stringResource(it.labelRes()) }
    return remember(graph, snackbar, reasonTexts) { Actions(graph, snackbar, scope, reasonTexts) }
}

@Composable
fun MoneyText(
    money: Money,
    currency: String,
    modifier: Modifier = Modifier,
    colored: Boolean = false,
    bold: Boolean = false,
) {
    val color = when {
        !colored -> Color.Unspecified
        money.isNegative -> MoneyColors.negative
        money.isPositive -> MoneyColors.positive
        else -> Color.Unspecified
    }
    Text(
        formatMoney(money, currency, language),
        modifier,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        maxLines = 1,
    )
}

@Composable
fun StatusChip(status: EventStatus, archived: Boolean = false) {
    val (label, container) = when {
        archived -> Res.string.status_archived to MaterialTheme.colorScheme.surfaceVariant
        status == EventStatus.DRAFT -> Res.string.status_draft to MaterialTheme.colorScheme.surfaceVariant
        status == EventStatus.OPEN -> Res.string.status_open to MaterialTheme.colorScheme.tertiaryContainer
        status == EventStatus.SETTLED -> Res.string.status_settled to MaterialTheme.colorScheme.secondaryContainer
        else -> Res.string.status_loss to MaterialTheme.colorScheme.errorContainer
    }
    // A label, not a button: an AssistChip would be announced (and focusable) as something to tap.
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(stringResource(label), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Avatar(name: String, size: Dp = 28.dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .clearAndSetSemantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Decimal input for amounts; [onValid] receives the parsed amount or null when the text is not a valid amount. */
@Composable
fun MoneyField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    val parsed = parseMoney(text)
    val invalid = text.isNotBlank() && (parsed == null || (!allowNegative && parsed.isNegative))
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(Res.string.amount_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

fun parseMoney(text: String): Money? = runCatching { Money.parse(text.replace(" ", "").replace(" ", "")) }.getOrNull()

@Composable
fun ConfirmDialog(title: String, text: String?, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Dialog with a single text field, e.g. for names. */
@Composable
fun TextInputDialog(title: String, label: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        // Lets screen reader users jump between the sections of long screens (the event screen).
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * "From → To" for transfers. The arrow is an icon: the web build's bundled font has no arrow glyph.
 */
@Composable
fun TransferParties(from: String, to: String, modifier: Modifier = Modifier) {
    // The arrow is an icon: read the pair as one phrase ("Alice to Bob") instead of two unrelated names.
    val description = stringResource(Res.string.transfer_a11y, from, to)
    Row(modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Text(from)
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
        )
        Text(to)
    }
}

/** Records only part of a suggested transfer, e.g. the cash someone has on them; the rest stays suggested. */
@Composable
fun PartialSettleDialog(
    from: String,
    to: String,
    suggested: Money,
    currency: String,
    onConfirm: (Money) -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = language
    var amount by remember { mutableStateOf(formatMoneyInput(suggested, lang)) }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive && it <= suggested }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settle_part_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferParties(from, to)
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount))
                Text(
                    stringResource(Res.string.settle_part_hint, formatMoney(suggested, currency, lang)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { onConfirm(parsed!!); onDismiss() }) {
                Text(stringResource(Res.string.settle))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Visible edit affordance at the end of a tappable row (touch screens show no hover highlight). */
@Composable
fun EditButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.Edit, stringResource(Res.string.edit)) }
}

/** Visible remove affordance at the end of a tappable row. */
@Composable
fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.Delete, stringResource(Res.string.remove)) }
}

/** The app version in a corner (sign-in screen, Settings), so it is easy to tell which build is running. */
@Composable
fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        AppConfig.VERSION_LABEL,
        modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}
