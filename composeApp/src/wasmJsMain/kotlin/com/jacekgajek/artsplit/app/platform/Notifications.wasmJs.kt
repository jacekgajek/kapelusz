package com.jacekgajek.artsplit.app.platform

import androidx.compose.runtime.Composable

// Notifications are Android only (VERSION_2: no web push).
@Composable
actual fun rememberNotificationPermission(): NotificationPermission? = null
