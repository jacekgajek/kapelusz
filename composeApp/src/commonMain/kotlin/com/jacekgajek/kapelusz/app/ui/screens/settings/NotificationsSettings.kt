package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.notifications_allow
import com.jacekgajek.kapelusz.app.generated.notifications_blocked
import com.jacekgajek.kapelusz.app.generated.notifications_days
import com.jacekgajek.kapelusz.app.generated.notifications_days_before
import com.jacekgajek.kapelusz.app.generated.notifications_reminders
import com.jacekgajek.kapelusz.app.generated.notifications_time
import com.jacekgajek.kapelusz.app.generated.notifications_title
import com.jacekgajek.kapelusz.app.generated.notifications_transfers
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.notify.NotificationRules
import com.jacekgajek.kapelusz.app.platform.NotificationPermission
import com.jacekgajek.kapelusz.app.ui.components.TimePickerDialog
import com.jacekgajek.kapelusz.app.ui.model.formatTime
import com.jacekgajek.kapelusz.app.ui.model.label
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Android: gig reminders and transfer notifications (ITERATION_2 §5.4); stored per device. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsSection(permission: NotificationPermission, settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var pickingTime by remember { mutableStateOf(false) }
    SectionHeader(stringResource(Res.string.notifications_title))
    if (permission.needsAsking && (settings.remindersEnabled || settings.transferNotifications)) {
        Text(stringResource(Res.string.notifications_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { permission.request { granted -> if (!granted) permission.openSettings() } }) { Text(stringResource(Res.string.notifications_allow)) }
    }
    SwitchRow(stringResource(Res.string.notifications_reminders), settings.remindersEnabled) { on ->
        update { it.copy(remindersEnabled = on, notificationPromptDone = true) }
        if (on && permission.needsAsking) permission.request {}
    }
    if (settings.remindersEnabled) {
        Text(stringResource(Res.string.notifications_days_before), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationRules.LEAD_DAYS.forEach { days ->
                val selected = days in settings.reminderDays
                FilterChip(
                    selected = selected,
                    // At least one lead time stays ticked; turn reminders off with the switch instead.
                    onClick = { update { it.copy(reminderDays = if (selected && it.reminderDays.size > 1) it.reminderDays - days else it.reminderDays + days) } },
                    label = { Text(pluralStringResource(Res.plurals.notifications_days, days, days)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.notifications_time), Modifier.weight(1f))
            TextButton(onClick = { pickingTime = true }) { Text(formatTime(settings.reminderTime)) }
        }
    }
    SwitchRow(stringResource(Res.string.notifications_transfers), settings.transferNotifications) { on -> update { it.copy(transferNotifications = on) } }
    if (pickingTime) {
        TimePickerDialog(settings.reminderTime, stringResource(Res.string.notifications_time), onTime = { time -> update { it.copy(reminderTime = time) } }, onDismiss = { pickingTime = false })
    }
}
