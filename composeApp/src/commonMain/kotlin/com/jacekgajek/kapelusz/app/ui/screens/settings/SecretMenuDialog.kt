package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.nowMillis
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.copy_raw_data
import com.jacekgajek.kapelusz.app.generated.diag_battery_optimised
import com.jacekgajek.kapelusz.app.generated.diag_battery_unrestricted
import com.jacekgajek.kapelusz.app.generated.diag_none_scheduled
import com.jacekgajek.kapelusz.app.generated.diag_notifications
import com.jacekgajek.kapelusz.app.generated.diag_open_settings
import com.jacekgajek.kapelusz.app.generated.diag_reminders
import com.jacekgajek.kapelusz.app.generated.diag_test
import com.jacekgajek.kapelusz.app.generated.diag_test_set
import com.jacekgajek.kapelusz.app.generated.diag_test_text
import com.jacekgajek.kapelusz.app.generated.diag_test_title
import com.jacekgajek.kapelusz.app.generated.language
import com.jacekgajek.kapelusz.app.generated.raw_data_copied
import com.jacekgajek.kapelusz.app.generated.raw_data_failed
import com.jacekgajek.kapelusz.app.generated.secret_menu
import com.jacekgajek.kapelusz.app.platform.ReminderDiagnostics
import com.jacekgajek.kapelusz.app.platform.rememberCopy
import com.jacekgajek.kapelusz.app.platform.rememberReminderDiagnostics
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.app.ui.model.formatTime
import com.jacekgajek.kapelusz.app.ui.model.label
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Why gig reminders might not arrive on this phone, and a test reminder (Android). */
@Composable
private fun ReminderChecks(checks: ReminderDiagnostics) {
    var testAt by remember { mutableStateOf<String?>(null) }
    val testTitle = stringResource(Res.string.diag_test_title)
    val testText = stringResource(Res.string.diag_test_text)
    val lang = language
    fun at(millis: Long): String {
        val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${formatDate(time.date, lang)} ${formatTime(time.time)}"
    }
    Text(stringResource(Res.string.diag_reminders), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    @Composable
    fun check(ok: Boolean, label: StringResource) = Text(
        (if (ok) "✓ " else "✗ ") + stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
    check(checks.notificationsAllowed, Res.string.diag_notifications)
    // Information, not a fault: optimised is Android's default, and reminders arrive with it (tested on realme).
    Text(
        stringResource(if (checks.batteryUnrestricted) Res.string.diag_battery_unrestricted else Res.string.diag_battery_optimised),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = checks.openAppSettings) { Text(stringResource(Res.string.diag_open_settings)) }
    if (checks.scheduled.isEmpty()) Text(stringResource(Res.string.diag_none_scheduled), style = MaterialTheme.typography.bodySmall)
    checks.scheduled.take(5).forEach { (millis, name) -> Text("${at(millis)} · $name", style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = {
        checks.test(testTitle, testText)
        testAt = at(nowMillis() + 60_000)
    }) { Text(stringResource(Res.string.diag_test)) }
    testAt?.let { Text(stringResource(Res.string.diag_test_set, it), style = MaterialTheme.typography.bodySmall) }
}

/** Tools for admins: copy the band's raw data, e.g. to look into a problem. */
@Composable
internal fun SecretMenuDialog(rawData: suspend () -> String, onDismiss: () -> Unit) {
    val copy = rememberCopy()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<StringResource?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.secret_menu)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        result = runSuspendCatching { rawData() }.fold(
                            onSuccess = { if (copy(it)) Res.string.raw_data_copied else Res.string.raw_data_failed },
                            onFailure = { Res.string.raw_data_failed },
                        )
                        busy = false
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.ContentCopy, null)
                    Text(stringResource(Res.string.copy_raw_data), Modifier.padding(start = 8.dp))
                }
                result?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                rememberReminderDiagnostics()?.let { ReminderChecks(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}
