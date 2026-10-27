package com.jacekgajek.kapelusz.android

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import java.io.File

/** The Google Play build (ITERATION_7 §1.1): Play updates it, so there is nothing to set up. */
internal object Flavor {
    fun install() {}

    /**
     * A phone that came from the website's APK (an ordinary update) still has what its updater left: the scheduled
     * checks (whose worker this build doesn't have, so WorkManager would fail them), the notification channel and a
     * downloaded APK. Gone on the first start; cheap and harmless when there is nothing.
     */
    fun onStart(context: Context) {
        val app = context.applicationContext
        // The names the website APK's UpdateWorker and Updater use.
        WorkManager.getInstance(app).run {
            cancelUniqueWork("update-check")
            cancelUniqueWork("update-now")
        }
        app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel("updates")
        File(app.cacheDir, "updates").deleteRecursively()
    }

    fun onStop() {}
}
