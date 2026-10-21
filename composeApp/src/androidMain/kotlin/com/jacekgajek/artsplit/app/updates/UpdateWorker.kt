package com.jacekgajek.artsplit.app.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jacekgajek.artsplit.app.AndroidAppGraph
import java.util.concurrent.TimeUnit

/** The daily update check (and installing a downloaded update once the app is in the background). */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (inputData.getBoolean(INSTALL_ONLY, false)) {
            Updater.installIfReady(context)
            return Result.success()
        }
        if (!AndroidAppGraph.get(context).settingsForBackground().autoUpdate) return Result.success()
        return if (Updater.checkAndDownload(context)) Result.success() else Result.retry()
    }

    companion object {
        private const val DAILY = "update-check"
        private const val NOW = "update-now"
        private const val INSTALL_ONLY = "installOnly"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Keeps the daily check scheduled (idempotent); called on app start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(network).build(),
            )
        }

        /** Checks right away, e.g. when the app starts. */
        fun checkSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network).build(),
            )
        }

        /** The app left the foreground: install what is downloaded. */
        fun installWhenIdle(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "update-install",
                ExistingWorkPolicy.REPLACE,
                // A short delay: rotating the screen also stops the activity for a moment.
                OneTimeWorkRequestBuilder<UpdateWorker>().setInputData(workDataOf(INSTALL_ONLY to true))
                    .setInitialDelay(10, TimeUnit.SECONDS).build(),
            )
        }
    }
}
