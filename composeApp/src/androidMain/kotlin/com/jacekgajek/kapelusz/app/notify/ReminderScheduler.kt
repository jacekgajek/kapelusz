package com.jacekgajek.kapelusz.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.jacekgajek.kapelusz.app.AndroidAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Gig reminders (and debt reminders, ITERATION_3 §2.2) as inexact alarms, so no exact-alarm permission is needed (ITERATION_2 §5.2): from Android 12 on they
 * arrive within about an hour of the set time. Rescheduling replaces
 * everything scheduled before, so removed gigs or changed settings never leave stale reminders.
 */
object ReminderScheduler {
    private const val PREFS = "kapelusz-reminders"
    private const val KEY_SCHEDULED = "scheduled"
    /** "epochMillis|title" of each scheduled reminder, for the secret menu. */
    private const val KEY_TIMES = "times"
    private const val TEST_KEY = "test"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_LANGUAGE = "language"
    /** Reminders shown already, so planning again does not bring them back (the latest few are enough). */
    private const val KEY_SHOWN = "shown"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"
    private const val EXTRA_NAVIGATE = "navigate"

    /** Gig [reminders] and [debts] reminders (ITERATION_3 §2.2) go through the same alarms and bookkeeping. */
    fun reschedule(context: Context, reminders: List<Reminder>, language: String? = null, debts: List<DebtReminder> = emptyList()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach { key -> alarms.cancel(pending(context, key, null)) }
        val times = mutableSetOf<String>()
        fun add(key: String, at: LocalDateTime, eventName: String, intent: Intent) {
            val millis = at.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            schedule(alarms, millis, pending(context, key, intent.putExtra(EXTRA_KEY, key).putExtra(EXTRA_LANGUAGE, language)))
            times += ReminderLog.scheduledEntry(millis, eventName)
        }
        reminders.forEach { reminder ->
            val (title, text) = Notifier.reminderTexts(reminder.eventName, reminder.daysBefore, reminder.date, reminder.firstTime, language)
            add(reminder.key, reminder.at, reminder.eventName, intent(context, reminder.bandId, reminder.eventId, title, text).putExtra(EXTRA_NAVIGATE, reminder.navigateUrl))
        }
        debts.forEach { reminder ->
            val (title, text) = Notifier.debtTexts(reminder, language)
            add(reminder.key, reminder.at, reminder.eventName, intent(context, reminder.bandId, reminder.eventId, title, text).putExtra(Notifier.EXTRA_TO_SETTLE, true))
        }
        prefs.edit {
            putStringSet(KEY_SCHEDULED, (reminders.map { it.key } + debts.map { it.key }).toSet())
            putStringSet(KEY_TIMES, times)
        }
    }

    private fun intent(context: Context, bandId: String, eventId: String, title: String, text: String) = Intent(context, ReminderReceiver::class.java)
        .putExtra(Notifier.EXTRA_BAND, bandId)
        .putExtra(Notifier.EXTRA_EVENT, eventId)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_TEXT, text)

    /**
     * Allowed to go off while the phone is idle (Doze), on every version (V5-M1 D2a): a plain window alarm before
     * Android 12 waits for the phone to wake up, which may be hours later.
     */
    private fun schedule(alarms: AlarmManager, at: Long, pending: PendingIntent) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** The scheduled reminders, soonest first: when (epoch millis) and for which gig. */
    fun scheduled(context: Context): List<Pair<Long, String>> =
        ReminderLog.scheduled(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TIMES, emptySet()).orEmpty())

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

    /** The keys of reminders shown already. */
    fun shown(context: Context): Set<String> = ReminderLog.shownAt(shownEntries(context)).keys

    /** Stored as "<shown at, epoch ms>|<key>", so the oldest can be dropped. */
    private fun shownEntries(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SHOWN, emptySet()).orEmpty()

    internal suspend fun show(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_KEY)?.let { key ->
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit { putStringSet(KEY_SHOWN, ReminderLog.withShown(shownEntries(context), key, System.currentTimeMillis())) }
        }
        // Both are missing for the test reminder, which just opens the app.
        val band = intent.getStringExtra(Notifier.EXTRA_BAND)
        val event = intent.getStringExtra(Notifier.EXTRA_EVENT)
        // A debt reminder has its own notification, so it never replaces the event's gig reminder.
        val toSettle = intent.getBooleanExtra(Notifier.EXTRA_TO_SETTLE, false)
        var title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        var text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (toSettle) {
            when (val check = checkDebt(context, intent.getStringExtra(EXTRA_KEY))) {
                DebtCheck.Gone -> return
                is DebtCheck.Due -> Notifier.debtTexts(check.reminder, check.language).let { (t, x) -> title = t; text = x }
                // Kept as planned: better a reminder from the alarm than none.
                DebtCheck.Unknown -> Unit
            }
            // The debt may have been paid on another device: fetch it, so the next reminders are planned from that.
            BackgroundSyncWorker.syncSoon(context)
        }
        // An organiser's fee (ITERATION_4 §2.2) has its own notification too: it comes at the same time as a debt one.
        val fee = intent.getStringExtra(EXTRA_KEY)?.substringAfterLast('/')?.startsWith("fee") == true
        Notifier.showReminder(
            context, ((if (fee) "f" else if (toSettle) "d" else "r") + band + event).hashCode(), band, event,
            title, text, intent.getStringExtra(EXTRA_NAVIGATE), intent.getStringExtra(EXTRA_LANGUAGE), toSettle, openAtTop = fee,
        )
    }

    private sealed interface DebtCheck {
        data object Gone : DebtCheck
        data class Due(val reminder: DebtReminder, val language: String?) : DebtCheck
        data object Unknown : DebtCheck
    }

    /**
     * Whether the debt reminder [key] is still due, from the bands on this device (ITERATION_3 §2.2): the alarm holds
     * the texts from when it was planned, and the debt may have been paid since. [DebtCheck.Unknown] when the bands
     * cannot be loaded.
     */
    private suspend fun checkDebt(context: Context, key: String?): DebtCheck {
        if (key == null) return DebtCheck.Unknown
        return try {
            // On the main thread like the background sync: band data is only ever touched there.
            withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    val graph = AndroidAppGraph.get(context)
                    // Signed out: nothing of the previous user may remind anyone.
                    val userId = graph.loadForBackground() ?: return@withContext DebtCheck.Gone
                    val settings = graph.settingsForBackground()
                    if (!settings.debtReminders) return@withContext DebtCheck.Gone
                    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                    NotificationRules.dueDebtReminder(graph.bands.allBands(), userId, key, settings.reminderTime, now)
                        ?.let { DebtCheck.Due(it, settings.language) } ?: DebtCheck.Gone
                }
            } ?: DebtCheck.Unknown
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebtCheck.Unknown
        }
    }

    /** A broadcast receiver has about ten seconds; loading the bands must leave time for the notification. */
    private const val LOAD_TIMEOUT_MS = 5_000L
}

/** A gig reminder is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A debt reminder loads the bands first, so the receiver is kept alive until it is shown.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                ReminderScheduler.show(context.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms do not survive a restart or an app update, and a new time zone moves them: schedule the reminders again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED)) {
            BackgroundSyncWorker.refreshLocal(context.applicationContext)
        }
    }
}
