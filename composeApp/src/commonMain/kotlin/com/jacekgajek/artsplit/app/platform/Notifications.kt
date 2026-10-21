package com.jacekgajek.artsplit.app.platform

import androidx.compose.runtime.Composable

/** Android's notification permission (needed from Android 13); null where the app shows no notifications (web). */
interface NotificationPermission {
    /** Notifications can be shown now. */
    val granted: Boolean

    /** Android would need the user's permission first. */
    val needsAsking: Boolean

    /** Asks Android for the permission; [onResult] tells whether it was granted. */
    fun request(onResult: (Boolean) -> Unit)

    /** Android no longer asks (declined before): open the app's notification settings instead. */
    fun openSettings()
}

@Composable
expect fun rememberNotificationPermission(): NotificationPermission?
