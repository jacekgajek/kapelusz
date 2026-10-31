package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.account_invalid
import com.jacekgajek.kapelusz.app.generated.account_number
import com.jacekgajek.kapelusz.app.generated.blik_invalid
import com.jacekgajek.kapelusz.app.generated.blik_phone
import com.jacekgajek.kapelusz.app.generated.blik_remove
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.save
import org.jetbrains.compose.resources.stringResource

/**
 * A BLIK number or a bank account to set, change or remove: your own in this band (ITERATION_5 §8, ITERATION_7 §7), or
 * the band fund's.
 */
@Composable
fun PaymentDetailDialog(kind: PaymentKind, title: String, hint: String, current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current?.let(kind::display).orEmpty()) }
    val normalized = kind.normalize(text)
    val invalid = text.isNotBlank() && normalized == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(stringResource(if (kind == PaymentKind.BLIK) Res.string.blik_phone else Res.string.account_number)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) {
                        { Text(stringResource(if (kind == PaymentKind.BLIK) Res.string.blik_invalid else Res.string.account_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = kind.keyboard),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null, onClick = {
                val value = normalized ?: return@TextButton
                // Shown as read (a Polish number without +48): saved only if it reads differently.
                if (current == null || kind.display(value) != kind.display(current)) onSave(value)
                onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = {
                        onSave(null)
                        onDismiss()
                    }) { Text(stringResource(Res.string.blik_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
