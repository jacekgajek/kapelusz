package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.blik_invalid
import com.jacekgajek.kapelusz.app.generated.blik_mine
import com.jacekgajek.kapelusz.app.generated.blik_mine_hint
import com.jacekgajek.kapelusz.app.generated.blik_phone
import com.jacekgajek.kapelusz.app.generated.blik_remove
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.save
import com.jacekgajek.kapelusz.domain.BlikPhone
import org.jetbrains.compose.resources.stringResource

/** Your own BLIK number in this band (ITERATION_5 §8): set, change or remove. */
@Composable
internal fun BlikPhoneDialog(current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current?.let(BlikPhone::display).orEmpty()) }
    val normalized = BlikPhone.normalize(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.blik_mine)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.blik_mine_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    text,
                    { text = it },
                    label = { Text(stringResource(Res.string.blik_phone)) },
                    singleLine = true,
                    isError = text.isNotBlank() && normalized == null,
                    supportingText = if (text.isNotBlank() && normalized == null) { { Text(stringResource(Res.string.blik_invalid)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null, onClick = {
                val phone = normalized ?: return@TextButton
                // Shown as read (a Polish number without +48): saved only if it reads differently.
                if (current == null || BlikPhone.display(phone) != BlikPhone.display(current)) onSave(phone)
                onDismiss()
            }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = {
                    onSave(null)
                    onDismiss()
                }) { Text(stringResource(Res.string.blik_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
