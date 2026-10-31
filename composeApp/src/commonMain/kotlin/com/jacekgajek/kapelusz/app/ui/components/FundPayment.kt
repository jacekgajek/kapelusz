package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.account_number
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.blik_mine_off
import com.jacekgajek.kapelusz.app.generated.blik_title
import com.jacekgajek.kapelusz.app.generated.fund_account
import com.jacekgajek.kapelusz.app.generated.fund_blik
import com.jacekgajek.kapelusz.app.generated.fund_pay_in_add_hint
import com.jacekgajek.kapelusz.app.generated.fund_payment_hint
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetFundBankAccount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import org.jetbrains.compose.resources.stringResource

/**
 * Where to pay into the band fund, in its settings: its BLIK number and bank account, each with a copy
 * button; with [onSave] (its holder and leads), an edit button each, and the ones not set yet.
 */
@Composable
fun FundPaymentDetails(blikPhone: String?, bankAccount: String?, onSave: ((PaymentKind, String?) -> Unit)?) {
    var editing by remember { mutableStateOf<PaymentKind?>(null) }
    val fund = stringResource(Res.string.band_fund)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (blikPhone == null && bankAccount == null && onSave != null) {
            Text(stringResource(Res.string.fund_pay_in_add_hint), style = MaterialTheme.typography.bodySmall)
        }
        listOf(PaymentKind.BLIK to blikPhone, PaymentKind.ACCOUNT to bankAccount).forEach { (kind, stored) ->
            val label = stringResource(if (kind == PaymentKind.BLIK) Res.string.blik_title else Res.string.account_number)
            if (stored != null) {
                PaymentRow(kind, label, stored, fund) { if (onSave != null) EditButton(label) { editing = kind } }
            } else if (onSave != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(Res.string.blik_mine_off), style = MaterialTheme.typography.bodyLarge)
                    }
                    EditButton(label) { editing = kind }
                }
            }
        }
    }
    editing?.let { kind ->
        if (onSave != null) {
            PaymentDetailDialog(
                kind,
                stringResource(if (kind == PaymentKind.BLIK) Res.string.fund_blik else Res.string.fund_account),
                stringResource(Res.string.fund_payment_hint),
                if (kind == PaymentKind.BLIK) blikPhone else bankAccount,
                onSave = { onSave(kind, it) },
                onDismiss = { editing = null },
            )
        }
    }
}

/** The operation setting the band fund's number of [kind] to [value] (null removes it). */
fun fundPaymentOp(kind: PaymentKind, value: String?): Operation = when (kind) {
    PaymentKind.BLIK -> SetFundBlikPhone(value)
    PaymentKind.ACCOUNT -> SetFundBankAccount(value)
}
