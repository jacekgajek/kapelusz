package com.jacekgajek.artsplit.app.notify

import android.content.Context
import androidx.core.content.edit
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
import com.jacekgajek.artsplit.app.data.AppGraph
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/**
 * The background sync (ITERATION_2 §5.1): every few hours, all bands; then gig reminders are scheduled again and new
 * transfers involving the user are notified. The local variant (after a restart, or when the app leaves the screen)
 * only reschedules from the data on the device and marks what the user has just seen.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // On the main thread: band data is only ever touched there (the UI, the foreground sync), never concurrently.
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val graph = AndroidAppGraph.get(applicationContext)
        val userId = graph.loadForBackground()
        if (userId == null) {
            // Signed out: nothing of the previous user may remind anyone.
            ReminderScheduler.reschedule(applicationContext, emptyList())
            return@withContext Result.success()
        }
        val local = inputData.getBoolean(LOCAL, false)
        val synced = local || graph.sync.syncAll()
        afterSync(applicationContext, graph, userId, notifyTransfers = !local)
        if (synced) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "background-sync"
        private const val LOCAL = "local"
        private const val PREFS = "art-split-transfers"

        fun schedule(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackgroundSyncWorker>(4, TimeUnit.HOURS).setConstraints(network).build(),
            )
        }

        /** Reschedule reminders from the data on the device (no network), e.g. after the app was used. */
        fun refreshLocal(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminders-local",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackgroundSyncWorker>().setInputData(workDataOf(LOCAL to true)).build(),
            )
        }

        /** One at a time: the periodic and the local runs both reschedule alarms and update what was seen. */
        private val afterSyncLock = Mutex()

        /** Gig reminders from the data on the device; also right away when settings or bands change in the app. */
        internal suspend fun rescheduleReminders(context: Context, graph: AppGraph, userId: String) = afterSyncLock.withLock { reminders(context, graph, userId) }

        private suspend fun reminders(context: Context, graph: AppGraph, userId: String) {
            val settings = graph.settingsForBackground()
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            ReminderScheduler.reschedule(
                context,
                if (settings.remindersEnabled) NotificationRules.reminders(graph.bands.allBands(), userId, settings.reminderDays, settings.reminderTime, now) else emptyList(),
                settings.language,
            )
        }

        internal suspend fun afterSync(context: Context, graph: AppGraph, userId: String, notifyTransfers: Boolean) = afterSyncLock.withLock {
            reminders(context, graph, userId)
            val settings = graph.settingsForBackground()
            val bands = graph.bands.allBands()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val check = NotificationRules.checkTransfers(
                bands,
                userId,
                seen = prefs.getStringSet("seen-$userId", null).orEmpty(),
                baselined = prefs.getStringSet("baselined-$userId", null).orEmpty(),
            )
            if (notifyTransfers && settings.transferNotifications) {
                check.notices.forEach { notice -> bands.firstOrNull { it.id == notice.bandId }?.let { Notifier.showTransfers(context, it, notice, settings.language) } }
            }
            prefs.edit {
                putStringSet("seen-$userId", check.seen)
                putStringSet("baselined-$userId", check.baselined)
            }
        }
    }
}
