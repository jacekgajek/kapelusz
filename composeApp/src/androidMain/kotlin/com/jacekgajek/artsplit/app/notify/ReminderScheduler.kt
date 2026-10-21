package com.jacekgajek.artsplit.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Gig reminders as inexact alarms (no exact-alarm permission needed; a few minutes late is fine). Rescheduling
 * replaces everything scheduled before, so removed gigs or changed settings never leave stale reminders.
 */
object ReminderScheduler {
    private const val PREFS = "art-split-reminders"
    private const val KEY_SCHEDULED = "scheduled"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"

    fun reschedule(context: Context, reminders: List<Reminder>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach { key -> alarms.cancel(pending(context, key, null)) }
        reminders.forEach { reminder ->
            val (title, text) = Notifier.reminderTexts(reminder.eventName, reminder.daysBefore, reminder.date, reminder.firstTime)
            val at = reminder.at.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(Notifier.EXTRA_BAND, reminder.bandId)
                .putExtra(Notifier.EXTRA_EVENT, reminder.eventId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, reminder.key, intent))
        }
        prefs.edit { putStringSet(KEY_SCHEDULED, reminders.map { it.key }.toSet()) }
    }

    private fun pending(context: Context, key: String, intent: Intent?): PendingIntent = PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        intent ?: Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun show(context: Context, intent: Intent) {
        val band = intent.getStringExtra(Notifier.EXTRA_BAND) ?: return
        val event = intent.getStringExtra(Notifier.EXTRA_EVENT) ?: return
        Notifier.showReminder(context, ("r$band$event").hashCode(), band, event, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_TEXT).orEmpty())
    }
}

/** A gig reminder is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderScheduler.show(context.applicationContext, intent)
}

/** Alarms do not survive a restart (or an app update): schedule the reminders again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackgroundSyncWorker.refreshLocal(context.applicationContext)
        }
    }
}
