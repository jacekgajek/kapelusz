package com.jacekgajek.kapelusz.app.sync

import android.app.AlarmManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
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

        /**
         * Once, what versions with notifications (before 1.12) left on the phone: the periodic sync and the reminders'
         * jobs, their alarms, the channels in Android's settings (and with them their notifications), and what was
         * shown or seen.
         */
        fun removeOldNotifications(context: Context) {
            val prefs = context.getSharedPreferences("kapelusz-cleanup", Context.MODE_PRIVATE)
            if (prefs.getBoolean(NOTIFICATIONS_REMOVED, false)) return
            WorkManager.getInstance(context).run {
                cancelUniqueWork("background-sync")
                cancelUniqueWork("reminders-local")
            }
            // The reminders' alarms, as they were made: the key's hash and the (now missing) receiver, on every version.
            val alarms = context.getSystemService(AlarmManager::class.java)
            val keys = context.getSharedPreferences("kapelusz-reminders", Context.MODE_PRIVATE).getStringSet("scheduled", emptySet()).orEmpty() + "test"
            keys.forEach { key ->
                val receiver = Intent().setClassName(context, "com.jacekgajek.kapelusz.app.notify.ReminderReceiver")
                PendingIntent.getBroadcast(context, key.hashCode(), receiver, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { pending ->
                    alarms.cancel(pending)
                    pending.cancel()
                }
            }
            NotificationManagerCompat.from(context).run { listOf("reminders", "transfers", "debts").forEach(::deleteNotificationChannel) }
            listOf("kapelusz-reminders", "kapelusz-transfers").forEach(context::deleteSharedPreferences)
            prefs.edit().putBoolean(NOTIFICATIONS_REMOVED, true).apply()
        }

        private const val NOTIFICATIONS_REMOVED = "notifications-removed"
    }
}
