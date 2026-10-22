package com.jacekgajek.artsplit.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Gig reminders as inexact alarms, so no exact-alarm permission is needed (VERSION_2 §5.2): from Android 12 on they
 * arrive within about an hour of the set time. Rescheduling replaces
 * everything scheduled before, so removed gigs or changed settings never leave stale reminders.
 */
object ReminderScheduler {
    private const val PREFS = "art-split-reminders"
    private const val KEY_SCHEDULED = "scheduled"
    /** "epochMillis|title" of each scheduled reminder, for the secret menu. */
    private const val KEY_TIMES = "times"
    private const val TEST_KEY = "test"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_NAVIGATE = "navigate"

    fun reschedule(context: Context, reminders: List<Reminder>, language: String? = null) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach { key -> alarms.cancel(pending(context, key, null)) }
        reminders.forEach { reminder ->
            val (title, text) = Notifier.reminderTexts(reminder.eventName, reminder.daysBefore, reminder.date, reminder.firstTime, language)
            val at = reminder.at.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(Notifier.EXTRA_BAND, reminder.bandId)
                .putExtra(Notifier.EXTRA_EVENT, reminder.eventId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_NAVIGATE, reminder.navigateUrl)
            schedule(alarms, at, pending(context, reminder.key, intent))
        }
        prefs.edit {
            putStringSet(KEY_SCHEDULED, reminders.map { it.key }.toSet())
            putStringSet(KEY_TIMES, reminders.map { "${it.at.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()}|${it.eventName}" }.toSet())
        }
    }

    /**
     * Before Android 12 an inexact alarm may come very late, so a 15-minute window is asked for; from 12 on,
     * allow-while-idle alarms arrive within about an hour.
     */
    private fun schedule(alarms: AlarmManager, at: Long, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 15 * 60 * 1000L, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** The scheduled reminders, soonest first: when (epoch millis) and for which gig. */
    fun scheduled(context: Context): List<Pair<Long, String>> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TIMES, emptySet()).orEmpty()
            .mapNotNull { entry -> entry.substringBefore('|').toLongOrNull()?.let { it to entry.substringAfter('|') } }
            .sortedBy { it.first }

    /** A test reminder in a minute, through the same alarm path as gig reminders (secret menu). */
    fun test(context: Context, title: String, text: String) {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
        schedule(context.getSystemService(AlarmManager::class.java), System.currentTimeMillis() + 60_000, pending(context, TEST_KEY, intent))
    }

    private fun pending(context: Context, key: String, intent: Intent?): PendingIntent = PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        intent ?: Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun show(context: Context, intent: Intent) {
        // Both are missing for the test reminder, which just opens the app.
        val band = intent.getStringExtra(Notifier.EXTRA_BAND)
        val event = intent.getStringExtra(Notifier.EXTRA_EVENT)
        Notifier.showReminder(
            context, ("r$band$event").hashCode(), band, event,
            intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_TEXT).orEmpty(), intent.getStringExtra(EXTRA_NAVIGATE),
        )
    }
}

/** A gig reminder is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReminderScheduler.show(context.applicationContext, intent)
}

/** Alarms do not survive a restart or an app update, and a new time zone moves them: schedule the reminders again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED)) {
            BackgroundSyncWorker.refreshLocal(context.applicationContext)
        }
    }
}
