package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.blik_copied
import com.jacekgajek.kapelusz.app.generated.blik_copy
import com.jacekgajek.kapelusz.app.generated.blik_numbers
import com.jacekgajek.kapelusz.app.generated.blik_partner
import com.jacekgajek.kapelusz.app.generated.blik_pay_to
import com.jacekgajek.kapelusz.app.generated.blik_title
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.domain.BlikPhone
import com.jacekgajek.kapelusz.model.Member
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The phone icon beside a transfer whose recipients gave a BLIK number (ITERATION_5 §8); it opens [BlikDialog]. Nothing
 * when [people] is empty. The caller shows it only to who may settle or record that transfer.
 */
@Composable
fun BlikButton(people: List<Member>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier) {
        Icon(Icons.Default.PhoneAndroid, stringResource(Res.string.blik_numbers), tint = MaterialTheme.colorScheme.primary)
    }
    if (open) BlikDialog(people, onDismiss = { open = false })
}

/** The recipients' BLIK numbers, each copied with its own button; the first is the one the transfer is recorded to. */
@Composable
fun BlikDialog(people: List<Member>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.blik_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.blik_pay_to), style = MaterialTheme.typography.bodySmall)
                people.forEachIndexed { index, member ->
                    BlikNumberRow(if (index == 0) member.name else stringResource(Res.string.blik_partner, member.name), member)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** One recipient's number with its copy button, e.g. also in "Hand over money". */
@Composable
fun BlikNumberRow(label: String, member: Member) {
    val stored = member.blikPhone ?: return
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    // Said in place: a snackbar would be under the dialog.
    var copied by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BlikPhone.display(stored), style = MaterialTheme.typography.titleMedium)
            if (copied) Text(stringResource(Res.string.blik_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { scope.launch { copied = copy(BlikPhone.copyText(stored)) } }) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                stringResource(Res.string.blik_copy, member.name),
                Modifier.size(20.dp),
            )
        }
    }
}
