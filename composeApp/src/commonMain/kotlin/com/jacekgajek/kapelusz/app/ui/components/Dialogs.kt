package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.amount
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.not_in_app_invite
import com.jacekgajek.kapelusz.app.generated.not_in_app_text
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.settle
import com.jacekgajek.kapelusz.app.generated.settle_part_hint
import com.jacekgajek.kapelusz.app.generated.settle_part_title
import com.jacekgajek.kapelusz.app.ui.format.formatMoney
import com.jacekgajek.kapelusz.model.Money
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** "Bob cannot see the band. Do you want to invite them?" for a member not in the app; Invite: [onInvite]. */
@Composable
fun InviteMemberDialog(name: String, onInvite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(Res.string.not_in_app_text, name)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onInvite()
            }) { Text(stringResource(Res.string.not_in_app_invite)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String?, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Dialog with a single text field, e.g. for names; [error] says why a text can't be taken (e.g. a name in use). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    error: @Composable (String) -> String? = { null },
    /** Whether OK is possible besides [error]: e.g. not while [below] offers something else. */
    allowed: (String) -> Boolean = { true },
    /** Under the field. */
    below: @Composable (String) -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    val problem = error(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                below(text)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && problem == null && allowed(text), onClick = {
                onConfirm(text.trim())
                onDismiss()
            }) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Records only part of a suggested transfer, e.g. the cash someone has on them; the rest stays suggested. */
@Composable
fun PartialSettleDialog(
    from: String,
    to: String,
    suggested: Money,
    currency: String,
    onConfirm: (Money) -> Boolean,
    onDismiss: () -> Unit,
    /** As on the suggestion's button: Paid, Received or Settle ([settleLabel]). */
    confirmLabel: StringResource = Res.string.settle,
    /** Whose refusals show in the dialog ([DialogNotice]). */
    actions: Actions? = null,
) {
    val lang = language
    // Empty and focused: the amount handed over is usually known, the suggestion stays in the hint below.
    var amount by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val parsed = parseMoney(amount)?.takeIf { it.isPositive && it <= suggested }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settle_part_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferParties(from, to)
                MoneyField(amount, { amount = it }, stringResource(Res.string.amount), Modifier.focusRequester(focus))
                Text(
                    stringResource(Res.string.settle_part_hint, formatMoney(suggested, currency, lang)),
                    style = MaterialTheme.typography.bodySmall,
                )
                actions?.let { DialogNotice(it) }
            }
        },
        confirmButton = {
            // Refused (e.g. more than an artist is owed now): the dialog stays, with the amount (I5 C4c).
            TextButton(enabled = parsed != null, onClick = { if (onConfirm(parsed!!)) onDismiss() }) {
                Text(stringResource(confirmLabel))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
