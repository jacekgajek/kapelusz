package com.jacekgajek.kapelusz.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jacekgajek.kapelusz.app.AndroidAppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Changes still on this device when the app leaves the screen go out as soon as there is a network (ITERATION_3 R4):
 * the foreground sync stops there. The app has no notifications any more (ITERATION_6 §9), so nothing else runs in
 * the background.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // On the main thread: band data is only ever touched there (the UI, the foreground sync), never concurrently.
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val graph = AndroidAppGraph.get(applicationContext)
        if (graph.loadForBackground() == null) return@withContext Result.success()
        if (graph.sync.syncAll()) Result.success() else Result.retry()
    }

    companion object {
        fun syncSoon(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-soon",
                ExistingWorkPolicy.REPLACE,
                // Not expedited: before Android 12 that needs a foreground notification.
                OneTimeWorkRequestBuilder<BackgroundSyncWorker>().setConstraints(network).build(),
            )
        }
    }
}
