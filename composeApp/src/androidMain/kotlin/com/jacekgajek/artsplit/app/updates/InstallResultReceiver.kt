package com.jacekgajek.artsplit.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Receives the result of an update install session started by [Updater]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val versionName = intent.getStringExtra(Updater.EXTRA_VERSION_NAME).orEmpty()
        Log.i(Updater.TAG, "Install result ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)}: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) Updater.onNeedsConfirmation(context.applicationContext, confirm, versionName) else Updater.onFailed()
            }
            // Success replaces this app; the new version starts fresh.
            PackageInstaller.STATUS_SUCCESS -> {}
            // Includes the user declining; the next check offers it again.
            else -> Updater.onFailed()
        }
    }
}
