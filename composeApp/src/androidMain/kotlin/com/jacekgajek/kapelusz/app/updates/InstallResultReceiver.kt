package com.jacekgajek.kapelusz.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/** Receives the result of an update install session started by [Updater]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val versionName = intent.getStringExtra(Updater.EXTRA_VERSION_NAME).orEmpty()
        val versionCode = intent.getLongExtra(Updater.EXTRA_VERSION_CODE, 0)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(Updater.TAG, "Install result $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // IntentCompat: the typed getParcelableExtra is unreliable on Android 13.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) Updater.onNeedsConfirmation(app, confirm, versionCode, versionName)
                else Updater.onFinishedWithoutInstall(app, versionCode, versionName, declined = false)
            }
            // Success replaces this app; the new version starts fresh.
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onFinishedWithoutInstall(app, versionCode, versionName, declined = true)
            else -> Updater.onFinishedWithoutInstall(app, versionCode, versionName, declined = false)
        }
    }
}
