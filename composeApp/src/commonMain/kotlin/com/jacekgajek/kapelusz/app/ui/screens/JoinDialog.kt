package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.app.data.Membership
import androidx.compose.foundation.layout.padding
import com.jacekgajek.kapelusz.app.generated.join_scan_qr
import com.jacekgajek.kapelusz.app.generated.join_qr_not_invite
import com.jacekgajek.kapelusz.app.platform.QrScan
import com.jacekgajek.kapelusz.app.platform.rememberQrScanner
import com.jacekgajek.kapelusz.app.generated.join_scan_failed
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.Icons
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
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
import com.jacekgajek.kapelusz.api.ApiErrors
import com.jacekgajek.kapelusz.api.InvitePreview
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.check_code
import com.jacekgajek.kapelusz.app.generated.invite_code
import com.jacekgajek.kapelusz.app.generated.join
import com.jacekgajek.kapelusz.app.generated.join_already_member
import com.jacekgajek.kapelusz.app.generated.join_band
import com.jacekgajek.kapelusz.app.generated.join_failed
import com.jacekgajek.kapelusz.app.generated.join_hint
import com.jacekgajek.kapelusz.app.generated.join_holds_money
import com.jacekgajek.kapelusz.app.generated.join_invalid_code
import com.jacekgajek.kapelusz.app.generated.join_preview
import com.jacekgajek.kapelusz.app.generated.join_still_plays
import com.jacekgajek.kapelusz.app.generated.join_too_many_attempts
import com.jacekgajek.kapelusz.app.sync.ApiException
import com.jacekgajek.kapelusz.app.ui.components.label
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val CODE_LENGTH = 6

/** Join a band with an invite code: check the code, see what it grants, then accept. */
@Composable
fun JoinDialog(membership: Membership, initialCode: String?, onJoined: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initialCode.orEmpty().uppercase()) }
    var preview by remember { mutableStateOf<InvitePreview?>(null) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun explain(e: Throwable): StringResource = when ((e as? ApiException)?.code) {
        ApiErrors.INVITE_NOT_FOUND -> Res.string.join_invalid_code
        ApiErrors.TOO_MANY_ATTEMPTS -> Res.string.join_too_many_attempts
        ApiErrors.ALREADY_MEMBER -> Res.string.join_already_member
        ApiErrors.HOLDS_MONEY -> Res.string.join_holds_money
        ApiErrors.STILL_PLAYS -> Res.string.join_still_plays
        else -> Res.string.join_failed
    }

    fun check() = scope.launch {
        busy = true
        error = null
        runSuspendCatching { membership.previewInvite(code) }.onSuccess { preview = it }.onFailure { error = explain(it) }
        busy = false
    }

    // A code from a join link is checked right away.
    LaunchedEffect(Unit) { if (code.length == CODE_LENGTH) check() }
    // An invite's QR code (ITERATION_6 §10): its join link, or a bare code; checked right away too.
    val scanQr = rememberQrScanner()
    var scanning by remember { mutableStateOf(false) }
    fun scan(scanner: suspend () -> QrScan) = scope.launch {
        scanning = true
        val result = scanner()
        scanning = false
        when (result) {
            QrScan.Cancelled -> {}
            QrScan.Failed -> error = Res.string.join_scan_failed
            is QrScan.Scanned -> {
                // Nothing from before stays joinable under the new result.
                preview = null
                val scanned = inviteCode(result.text)
                if (scanned == null) {
                    error = Res.string.join_qr_not_invite
                } else {
                    code = scanned
                    check()
                }
            }
        }
    }

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
                // Scanning is the usual way in, next to the leader's phone; typing the fallback.
                scanQr?.let { scanner ->
                    OutlinedButton(onClick = { scan(scanner) }, enabled = !busy && !scanning, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Text(stringResource(Res.string.join_scan_qr), Modifier.padding(start = 8.dp))
                    }
                }
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
                        runSuspendCatching { membership.joinBand(ready.code) }.onSuccess { onJoined() }.onFailure { error = explain(it); preview = null }
                        busy = false
                    }
                }) { Text(stringResource(Res.string.join)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The invite code in a scanned text: a join link's (…/join/ABC123, maybe with ?… or #… after it) or the code alone. */
internal fun inviteCode(text: String): String? {
    val trimmed = text.trim()
    val candidate = Regex("/join/([A-Za-z0-9]+)/?(?:[?#].*)?$").find(trimmed)?.groupValues?.get(1) ?: trimmed
    return candidate.uppercase().takeIf { it.length == CODE_LENGTH && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
}
