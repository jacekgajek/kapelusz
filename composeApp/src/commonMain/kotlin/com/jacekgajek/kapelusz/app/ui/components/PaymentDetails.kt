package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.account_copy
import com.jacekgajek.kapelusz.app.generated.account_numbers
import com.jacekgajek.kapelusz.app.generated.account_pay_to
import com.jacekgajek.kapelusz.app.generated.account_title
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.blik_copied
import com.jacekgajek.kapelusz.app.generated.blik_copy
import com.jacekgajek.kapelusz.app.generated.blik_numbers
import com.jacekgajek.kapelusz.app.generated.blik_partner
import com.jacekgajek.kapelusz.app.generated.blik_pay_to
import com.jacekgajek.kapelusz.app.generated.blik_title
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.domain.BankAccount
import com.jacekgajek.kapelusz.domain.BlikPhone
import com.jacekgajek.kapelusz.domain.Payee
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A way to be paid (ITERATION_5 §8, ITERATION_7 §2i): by BLIK to a phone number, or a transfer to a bank account. */
enum class PaymentKind(
    val icon: ImageVector,
    val title: StringResource,
    val payTo: StringResource,
    val iconLabel: StringResource,
    val copyLabel: StringResource,
    val keyboard: KeyboardType,
) {
    BLIK(Icons.Default.PhoneAndroid, Res.string.blik_title, Res.string.blik_pay_to, Res.string.blik_numbers, Res.string.blik_copy, KeyboardType.Phone),
    ACCOUNT(Icons.Default.AccountBalance, Res.string.account_title, Res.string.account_pay_to, Res.string.account_numbers, Res.string.account_copy, KeyboardType.Ascii);

    /** [payee]'s number of this kind, as stored. */
    fun of(payee: Payee): String? = when (this) {
        BLIK -> payee.blikPhone
        ACCOUNT -> payee.bankAccount
    }

    /** [input] as stored, null if not a number of this kind. */
    fun normalize(input: String): String? = when (this) {
        BLIK -> BlikPhone.normalize(input)
        ACCOUNT -> BankAccount.normalize(input)
    }

    fun display(stored: String): String = when (this) {
        BLIK -> BlikPhone.display(stored)
        ACCOUNT -> BankAccount.display(stored)
    }

    fun copyText(stored: String): String = when (this) {
        BLIK -> BlikPhone.copyText(stored)
        ACCOUNT -> BankAccount.copyText(stored)
    }
}

/**
 * The phone and bank icons beside a transfer, each shown when a recipient gave a number of that kind (ITERATION_5 §8,
 * ITERATION_7 §2i); each opens [PaymentDialog]. The caller shows them only to who may settle or record that transfer.
 */
@Composable
fun PaymentButtons(payees: List<Payee>) {
    PaymentKind.entries.forEach { kind -> PaymentButton(kind, payees.filter { kind.of(it) != null }, payees.firstOrNull()) }
}

@Composable
private fun PaymentButton(kind: PaymentKind, payees: List<Payee>, recordedTo: Payee?) {
    if (payees.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(kind.icon, stringResource(kind.iconLabel), tint = MaterialTheme.colorScheme.primary)
    }
    if (open) PaymentDialog(kind, payees, recordedTo, onDismiss = { open = false })
}

/**
 * The recipients' numbers of one kind, each copied with its own button; any but [recordedTo], whom the transfer is
 * recorded to, is marked as their partner (ITERATION_5 §8), also when only the partner gave a number of this kind.
 */
@Composable
fun PaymentDialog(kind: PaymentKind, payees: List<Payee>, recordedTo: Payee?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(kind.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(kind.payTo), style = MaterialTheme.typography.bodySmall)
                payees.forEach { payee ->
                    val name = payeeName(payee)
                    PaymentRow(kind, if (payee == recordedTo) name else stringResource(Res.string.blik_partner, name), kind.of(payee), name)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** A member's name, or "Band fund" for the fund's own details. */
@Composable
fun payeeName(payee: Payee): String = payee.member?.name ?: stringResource(Res.string.band_fund)

/**
 * One number with its copy button, e.g. also in "Hand over money" and on the band fund's tab; [whose] names it for the
 * copy button. Nothing without [stored]; [trailing] goes after the copy button, e.g. an edit button.
 */
@Composable
fun PaymentRow(kind: PaymentKind, label: String, stored: String?, whose: String, trailing: @Composable () -> Unit = {}) {
    if (stored == null) return
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    // Said in place: a snackbar would be under the dialog.
    var copied by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(kind.display(stored), style = MaterialTheme.typography.titleMedium)
            if (copied) Text(stringResource(Res.string.blik_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { scope.launch { copied = copy(kind.copyText(stored)) } }) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                stringResource(kind.copyLabel, whose),
                Modifier.size(20.dp),
            )
        }
        trailing()
    }
}
