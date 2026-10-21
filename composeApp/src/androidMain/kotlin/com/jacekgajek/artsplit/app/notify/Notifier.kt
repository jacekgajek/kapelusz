package com.jacekgajek.artsplit.app.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jacekgajek.artsplit.app.ui.model.formatDate
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.formatAgendaTime
import com.jacekgajek.artsplit.app.ui.model.partyName
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.Band
import java.util.Locale

/**
 * Shows gig reminders and transfer notifications (VERSION_2 §5). Texts are built here rather than from string
 * resources, since they appear from background work outside Compose; the language follows the default locale (the
 * app's choice while it runs, otherwise the system's). The wording avoids gendered Polish verb forms.
 */
object Notifier {
    private const val REMINDERS = "reminders"
    private const val TRANSFERS = "transfers"
    const val EXTRA_BAND = "openBand"
    const val EXTRA_EVENT = "openEvent"

    private val polish get() = Locale.getDefault().language == "pl"
    private val language get() = if (polish) "pl" else "en"

    fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REMINDERS, if (polish) "Przypomnienia o koncertach" else "Gig reminders", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(TRANSFERS, if (polish) "Przelewy" else "Transfers", NotificationManager.IMPORTANCE_LOW))
    }

    /** "Tomorrow: Club gig" / "Za 3 dni: Club gig", and the date with the first time ("Sat 12 Sep · Arrival 17:00"). */
    fun reminderTexts(eventName: String, daysBefore: Int, date: kotlinx.datetime.LocalDate, firstTime: AgendaTime?): Pair<String, String> {
        val whenText = when {
            daysBefore == 1 && polish -> "Jutro"
            daysBefore == 1 -> "Tomorrow"
            polish -> "Za $daysBefore dni"
            else -> "In $daysBefore days"
        }
        val time = firstTime?.let { "${label(it)} ${formatAgendaTime(it)}" }
        return "$whenText: $eventName" to listOfNotNull(formatDate(date, language), time).joinToString(" · ")
    }

    private fun label(time: AgendaTime) = when (time.kind) {
        AgendaKind.ARRIVAL -> if (polish) "Przyjazd" else "Arrival"
        AgendaKind.CONCERT_START -> if (polish) "Początek koncertu" else "Concert start"
        null -> time.label.orEmpty()
    }

    fun showReminder(context: Context, id: Int, bandId: String, eventId: String, title: String, text: String) {
        if (!canNotify(context)) return
        channels(context)
        notify(context, id, NotificationCompat.Builder(context, REMINDERS)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openEvent(context, id, bandId, eventId))
            .setAutoCancel(true)
            .build())
    }

    /** One notification per event, grouping what was recorded together. */
    fun showTransfers(context: Context, band: Band, notice: TransferNotice) {
        if (!canNotify(context)) return
        channels(context)
        val event = notice.event
        val fund = if (polish) "Kasa zespołowa" else "Band fund"
        val by = notice.recordedBy.mapNotNull { band.member(it)?.name }.joinToString(", ")
        val title: String
        val text: String
        if (notice.transfers.size == 1) {
            val t = notice.transfers.single()
            val line = "${partyName(band, event, t.from, fund)} → ${partyName(band, event, t.to, fund)}, ${formatMoney(t.amount, band.currency, language)}"
            title = if (polish) "Nowy przelew: $line" else "New transfer: $line"
        } else {
            val n = notice.transfers.size
            title = if (polish) "${event.name}: nowe przelewy z Twoim udziałem ($n)" else "${event.name}: $n new transfers with you"
        }
        text = listOfNotNull(event.name.takeIf { notice.transfers.size == 1 }, by.takeIf { it.isNotEmpty() }?.let { if (polish) "zapisane przez: $it" else "recorded by $it" })
            .joinToString(" · ")
        val id = ("t" + band.id + event.id).hashCode()
        notify(context, id, NotificationCompat.Builder(context, TRANSFERS)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openEvent(context, id, band.id, event.id))
            .setAutoCancel(true)
            .build())
    }

    private fun openEvent(context: Context, requestCode: Int, bandId: String, eventId: String): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_BAND, bandId)
            .putExtra(EXTRA_EVENT, eventId)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
