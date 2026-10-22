package com.jacekgajek.splitart.app.platform

import androidx.compose.runtime.Composable

// Notifications are Android only (ITERATION_2: no web push).
@Composable
actual fun rememberNotificationPermission(): NotificationPermission? = null

@Composable
actual fun rememberReminderDiagnostics(): ReminderDiagnostics? = null
