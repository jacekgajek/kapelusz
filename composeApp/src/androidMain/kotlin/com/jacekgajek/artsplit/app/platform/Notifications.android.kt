package com.jacekgajek.artsplit.app.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
