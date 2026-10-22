package com.jacekgajek.splitart.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.jacekgajek.splitart.api.ApiErrors
import com.jacekgajek.splitart.api.InvitePreview
import com.jacekgajek.splitart.app.data.AppGraph
import com.jacekgajek.splitart.app.generated.Res
import com.jacekgajek.splitart.app.generated.cancel
import com.jacekgajek.splitart.app.generated.check_code
import com.jacekgajek.splitart.app.generated.invite_code
import com.jacekgajek.splitart.app.generated.join
import com.jacekgajek.splitart.app.generated.join_already_member
import com.jacekgajek.splitart.app.generated.join_band
import com.jacekgajek.splitart.app.generated.join_failed
import com.jacekgajek.splitart.app.generated.join_hint
import com.jacekgajek.splitart.app.generated.join_invalid_code
import com.jacekgajek.splitart.app.generated.join_preview
import com.jacekgajek.splitart.app.generated.join_too_many_attempts
import com.jacekgajek.splitart.app.sync.ApiException
import com.jacekgajek.splitart.app.ui.components.label
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val CODE_LENGTH = 6

/** Join a band with an invite code: check the code, see what it grants, then accept. */
@Composable
fun JoinDialog(graph: AppGraph, initialCode: String?, onJoined: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initialCode.orEmpty().uppercase()) }
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun explain(e: Throwable): StringResource = when ((e as? ApiException)?.code) {
        ApiErrors.INVITE_NOT_FOUND -> Res.string.join_invalid_code
        ApiErrors.TOO_MANY_ATTEMPTS -> Res.string.join_too_many_attempts
        ApiErrors.ALREADY_MEMBER -> Res.string.join_already_member
        else -> Res.string.join_failed
    }

    fun check() = scope.launch {
        busy = true
        error = null
        runCatching { graph.api.previewInvite(code) }.onSuccess { preview = it }.onFailure { error = explain(it) }
        busy = false
    }

    // A code from a join link is checked right away.
    LaunchedEffect(Unit) { if (code.length == CODE_LENGTH) check() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.join_band)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.join_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    code,
                    { value ->
                        code = value.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH)
                        preview = null
                    },
                    label = { Text(stringResource(Res.string.invite_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                preview?.let { Text(stringResource(Res.string.join_preview, it.bandName, it.memberName, it.role.label()), style = MaterialTheme.typography.titleSmall) }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val ready = preview
            if (ready == null) {
                TextButton(enabled = !busy && code.length == CODE_LENGTH, onClick = { check() }) { Text(stringResource(Res.string.check_code)) }
            } else {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        runCatching { graph.joinBand(ready.code) }.onSuccess { onJoined() }.onFailure { error = explain(it); preview = null }
                        busy = false
                    }
                }) { Text(stringResource(Res.string.join)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
