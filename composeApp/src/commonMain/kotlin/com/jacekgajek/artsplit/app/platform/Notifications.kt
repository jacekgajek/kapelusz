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

/** What the secret menu shows about gig reminders (ITERATION_2 §7.1); Android only. */
class ReminderDiagnostics(
    val notificationsAllowed: Boolean,
    /** Not restricted by battery optimisation, which on some phones drops alarms of apps in the background. */
    val batteryUnrestricted: Boolean,
    /** The scheduled reminders, soonest first: when (epoch millis) and for which gig. */
    val scheduled: List<Pair<Long, String>>,
    /** A test reminder in a minute, through the same path as gig reminders. */
    val test: (title: String, text: String) -> Unit,
    /** Android's settings of this app, e.g. to allow background activity. */
    val openAppSettings: () -> Unit,
)

@Composable
expect fun rememberReminderDiagnostics(): ReminderDiagnostics?
