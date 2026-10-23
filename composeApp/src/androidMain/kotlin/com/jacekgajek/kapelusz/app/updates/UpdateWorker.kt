package com.jacekgajek.kapelusz.app.updates

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
import com.jacekgajek.kapelusz.app.AndroidAppGraph
import java.util.concurrent.TimeUnit

/** The daily update check: downloads a new build, which the user then installs (V5-M1 C1′). */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!AndroidAppGraph.get(context).settingsForBackground().autoUpdate) return Result.success()
        return if (Updater.checkAndDownload(context)) Result.success() else Result.retry()
    }

    companion object {
        private const val DAILY = "update-check"
        private const val NOW = "update-now"
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
    }
}
