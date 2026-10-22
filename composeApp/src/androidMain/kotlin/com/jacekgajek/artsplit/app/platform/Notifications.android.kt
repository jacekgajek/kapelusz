package com.jacekgajek.artsplit.app.platform

import com.jacekgajek.artsplit.app.notify.ReminderScheduler
import com.jacekgajek.artsplit.app.notify.Notifier
import android.net.Uri
import android.os.PowerManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberNotificationPermission(): NotificationPermission? {
    val context = LocalContext.current
    fun isGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(isGranted()) }
    // The user may allow notifications in Android's settings and come back: check again on every return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = isGranted() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var callback by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        callback(result)
    }
    val currentGranted by rememberUpdatedState(granted)
    return remember(launcher) {
        object : NotificationPermission {
            override val granted: Boolean get() = currentGranted
            override val needsAsking: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !currentGranted

            override fun request(onResult: (Boolean) -> Unit) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onResult(true)
                callback = onResult
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}

@Composable
actual fun rememberReminderDiagnostics(): ReminderDiagnostics? {
    val context = LocalContext.current
    // Read again on every return, e.g. after changing something in Android's settings.
    var refresh by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return remember(refresh) {
        ReminderDiagnostics(
            notificationsAllowed = Notifier.canNotify(context),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            scheduled = ReminderScheduler.scheduled(context),
            test = { title, text -> ReminderScheduler.test(context.applicationContext, title, text) },
            openAppSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        )
    }
}
