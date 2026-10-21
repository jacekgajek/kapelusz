package com.jacekgajek.artsplit.app.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jacekgajek.artsplit.app.ui.model.formatMoney
import com.jacekgajek.artsplit.app.ui.model.formatAgendaTime
import com.jacekgajek.artsplit.app.ui.model.partyName
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.BAND_FUND
import com.jacekgajek.artsplit.model.Band
import kotlinx.datetime.LocalDate
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

    /** The app's language when set, otherwise the device's (the default locale). */
    private fun polish(language: String?) = (language ?: Locale.getDefault().language) == "pl"

    fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channels(context: Context, pl: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REMINDERS, if (pl) "Przypomnienia o koncertach" else "Gig reminders", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(TRANSFERS, if (pl) "Przelewy" else "Transfers", NotificationManager.IMPORTANCE_LOW))
    }

    private val EN_DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val PL_DAYS = listOf("pon.", "wt.", "śr.", "czw.", "pt.", "sob.", "niedz.")
    private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "Sat 12 Sep" / "sob. 12.09" */
    private fun dayText(date: LocalDate, pl: Boolean): String {
        val day = date.dayOfWeek.ordinal
        return if (pl) "${PL_DAYS[day]} ${date.day.toString().padStart(2, '0')}.${(date.month.ordinal + 1).toString().padStart(2, '0')}"
        else "${EN_DAYS[day]} ${date.day} ${EN_MONTHS[date.month.ordinal]}"
    }

    /** "Tomorrow: Club gig" / "Za 3 dni: Club gig", and "Sat 12 Sep · Arrival 17:00". */
    fun reminderTexts(eventName: String, daysBefore: Int, date: LocalDate, firstTime: AgendaTime?, language: String? = null): Pair<String, String> {
        val pl = polish(language)
        val whenText = when {
            daysBefore == 1 && pl -> "Jutro"
            daysBefore == 1 -> "Tomorrow"
            pl -> "Za $daysBefore dni"
            else -> "In $daysBefore days"
        }
        val time = firstTime?.let { "${label(it, pl)} ${formatAgendaTime(it)}" }
        return "$whenText: $eventName" to listOfNotNull(dayText(date, pl), time).joinToString(" · ")
    }

    private fun label(time: AgendaTime, pl: Boolean) = when (time.kind) {
        AgendaKind.ARRIVAL -> if (pl) "Przyjazd" else "Arrival"
        AgendaKind.SOUND_CHECK -> if (pl) "Próba dźwięku" else "Sound check"
        AgendaKind.CONCERT_START -> if (pl) "Początek koncertu" else "Concert start"
        null -> time.label.orEmpty()
    }

    fun showReminder(context: Context, id: Int, bandId: String, eventId: String, title: String, text: String, navigateUrl: String?) {
        if (!canNotify(context)) return
        val pl = polish(null)
        channels(context, pl)
        val builder = NotificationCompat.Builder(context, REMINDERS)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openEvent(context, id, bandId, eventId))
            .setAutoCancel(true)
        // Straight to Google Maps for the gig's place.
        navigateUrl?.let { url ->
            val maps = PendingIntent.getActivity(
                context, id + 1, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, if (pl) "Nawiguj" else "Navigate", maps)
        }
        notify(context, id, builder.build())
    }

    /**
     * One notification per event and check: "129,60 zł from Dave" (to you), "… to Dave" (from you), "… from Dave to
     * the band fund" (which you hold), with who recorded it. Polish avoids gendered verb forms.
     */
    fun showTransfers(context: Context, band: Band, notice: TransferNotice, language: String?) {
        if (!canNotify(context)) return
        val pl = polish(language)
        channels(context, pl)
        val event = notice.event
        val fund = if (pl) "kasy zespołowej" else "the band fund"
        val lang = if (pl) "pl" else "en"
        fun name(id: String) = partyName(band, event, id, if (pl) "Kasa zespołowa" else "Band fund")
        val title = if (notice.transfers.size == 1) {
            val t = notice.transfers.single()
            val amount = formatMoney(t.amount, band.currency, lang)
            when {
                t.to == BAND_FUND -> if (pl) "$amount od: ${name(t.from)} do $fund" else "$amount from ${name(t.from)} to $fund"
                t.from == BAND_FUND -> if (pl) "$amount z $fund dla: ${name(t.to)}" else "$amount from $fund to ${name(t.to)}"
                t.to == notice.meId -> if (pl) "$amount od: ${name(t.from)}" else "$amount from ${name(t.from)}"
                else -> if (pl) "$amount dla: ${name(t.to)}" else "$amount to ${name(t.to)}"
            }
        } else {
            val n = notice.transfers.size
            if (pl) "Nowe przelewy z Twoim udziałem: $n" else "$n new transfers with you"
        }
        val by = notice.recordedBy.mapNotNull { band.member(it)?.name }.joinToString(", ")
        val text = listOfNotNull(event.name, by.takeIf { it.isNotEmpty() }?.let { if (pl) "zapisane przez: $it" else "recorded by $it" }).joinToString(" · ")
        val id = ("t" + band.id + event.id + notice.transfers.first().id).hashCode()
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
