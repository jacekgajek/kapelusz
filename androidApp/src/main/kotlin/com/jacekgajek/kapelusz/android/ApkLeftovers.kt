package com.jacekgajek.kapelusz.android

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import java.io.File

/**
 * A phone that came from the website's APK (an ordinary update, before 2026-10-27 the app was also handed out there)
 * still has what its updater left: the scheduled checks (whose worker this build doesn't have, so WorkManager would
 * fail them), the notification channel, a downloaded APK and its settings. Once per process: cheap and harmless when
 * there is nothing.
 */
internal object ApkLeftovers {
    private var cleaned = false

    fun clean(context: Context) {
        if (cleaned) return
        cleaned = true
        val app = context.applicationContext
        // The names the website APK's UpdateWorker and Updater use.
        WorkManager.getInstance(app).run {
            cancelUniqueWork("update-check")
            cancelUniqueWork("update-now")
        }
        app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel("updates")
        File(app.cacheDir, "updates").deleteRecursively()
        // The updater's state and the "update ready" strip's dismissals.
        app.deleteSharedPreferences("kapelusz-updater")
        app.deleteSharedPreferences("kapelusz-updates")
    }
}
