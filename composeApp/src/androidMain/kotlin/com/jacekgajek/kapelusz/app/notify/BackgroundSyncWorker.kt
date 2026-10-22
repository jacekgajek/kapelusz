package com.jacekgajek.kapelusz.app.notify

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
import com.jacekgajek.kapelusz.app.AndroidAppGraph
import com.jacekgajek.kapelusz.app.data.AppGraph
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
        afterSync(applicationContext, graph, userId, notifyTransfers = !local && !inputData.getBoolean(SOON, false))
        if (synced) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "background-sync"
        private const val LOCAL = "local"
        private const val SOON = "soon"
        private const val PREFS = "kapelusz-transfers"

        fun schedule(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackgroundSyncWorker>(4, TimeUnit.HOURS).setConstraints(network).build(),
            )
        }

        /**
         * A sync as soon as there is a network, for changes still on this device when the app leaves the screen
         * (ITERATION_3 R4): the foreground sync stops there, and the periodic one may be hours away.
         */
        fun syncSoon(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-soon",
                ExistingWorkPolicy.REPLACE,
                // Not expedited: before Android 12 that needs a foreground notification. Transfers are not notified: the
                // user has just seen them in the app.
                OneTimeWorkRequestBuilder<BackgroundSyncWorker>().setConstraints(network).setInputData(workDataOf(SOON to true)).build(),
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

        /** Gig and debt reminders from the data on the device; also right away when settings or bands change in the app. */
        internal suspend fun rescheduleReminders(context: Context, graph: AppGraph, userId: String) = afterSyncLock.withLock { reminders(context, graph, userId) }

        private suspend fun reminders(context: Context, graph: AppGraph, userId: String) {
            val settings = graph.settingsForBackground()
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val bands = graph.bands.allBands()
            val shown = ReminderScheduler.shown(context)
            ReminderScheduler.reschedule(
                context,
                if (settings.remindersEnabled) NotificationRules.reminders(bands, userId, settings.reminderDays, settings.reminderTime, now, shown) else emptyList(),
                settings.language,
                if (settings.debtReminders) NotificationRules.debtReminders(bands, userId, settings.reminderTime, now, shown) else emptyList(),
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
