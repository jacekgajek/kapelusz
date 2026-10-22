package com.jacekgajek.kapelusz.app.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jacekgajek.kapelusz.app.R
import com.jacekgajek.kapelusz.app.ui.model.formatMoney
import com.jacekgajek.kapelusz.app.ui.model.formatAgendaTime
import com.jacekgajek.kapelusz.app.ui.model.formatShortDate
import com.jacekgajek.kapelusz.app.ui.model.partyName
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import kotlinx.datetime.LocalDate
import java.util.Locale

/**
 * Shows gig reminders and transfer notifications (ITERATION_2 §5). Texts are built here rather than from string
 * resources, since they appear from background work outside Compose; the language follows the default locale (the
 * app's choice while it runs, otherwise the system's). The wording avoids gendered Polish verb forms.
 */
object Notifier {
    private const val REMINDERS = "reminders"
    private const val TRANSFERS = "transfers"
    private const val DEBTS = "debts"
    const val EXTRA_BAND = "openBand"
    const val EXTRA_EVENT = "openEvent"
    /** Opens the event at its To settle section (a debt reminder, ITERATION_3 §2.2). */
    const val EXTRA_TO_SETTLE = "openToSettle"

    /** The app's language when set, otherwise the device's (the default locale). */
    private fun polish(language: String?) = (language ?: Locale.getDefault().language) == "pl"

    fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun channels(context: Context, pl: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REMINDERS, if (pl) "Przypomnienia o koncertach" else "Gig reminders", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(TRANSFERS, if (pl) "Przelewy" else "Transfers", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(DEBTS, if (pl) "Przypomnienia o nierozliczonych długach" else "Reminders about open debts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** "Sat 12 Sep" / "sob. 12.09" */
    private fun dayText(date: LocalDate, pl: Boolean): String = formatShortDate(date, if (pl) "pl" else "en")

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

    /**
     * "You still owe Jacek 100.00 PLN" and "from Club gig (Sat 12 Sep)"; with more people owed, one line each
     * (ITERATION_3 §2.2). Polish avoids gendered verb forms.
     */
    fun debtTexts(reminder: DebtReminder, language: String? = null): Pair<String, String> {
        val pl = polish(language)
        val lang = if (pl) "pl" else "en"
        val gig = "${reminder.eventName} (${dayText(reminder.date, pl)})"
        fun line(owed: Owed): String {
            val amount = formatMoney(owed.amount, reminder.currency, lang)
            val to = when {
                owed.toName != null -> if (pl) "dla: ${owed.toName}" else owed.toName
                else -> if (pl) "do kasy zespołowej" else "the band fund"
            }
            val fromFund = if (!owed.fromFund) "" else if (pl) " (z kasy zespołowej)" else " (from the band fund)"
            return if (pl) "$amount $to$fromFund" else "$to $amount$fromFund"
        }
        val single = reminder.owed.singleOrNull()
        return when {
            single != null && pl -> "Do oddania: ${line(single)}" to gig
            single != null -> "You still owe ${line(single)}" to "from $gig"
            pl -> "Nierozliczone: $gig" to reminder.owed.joinToString("\n", transform = ::line)
            else -> "You still owe money from $gig" to reminder.owed.joinToString("\n", transform = ::line)
        }
    }

    private fun label(time: AgendaTime, pl: Boolean) = when (time.kind) {
        AgendaKind.ARRIVAL -> if (pl) "Przyjazd" else "Arrival"
        AgendaKind.SOUND_CHECK -> if (pl) "Próba dźwięku" else "Sound check"
        AgendaKind.CONCERT_START -> if (pl) "Początek koncertu" else "Concert start"
        null -> time.label.orEmpty()
    }

    /**
     * [language]: the app's (as the texts were made), so the action and the channel match them (ITERATION_3 R7).
     * [toSettle]: a debt reminder, opening the event at To settle (ITERATION_3 §2.2).
     */
    fun showReminder(context: Context, id: Int, bandId: String?, eventId: String?, title: String, text: String, navigateUrl: String?, language: String? = null, toSettle: Boolean = false) {
        if (!canNotify(context)) return
        val pl = polish(language)
        channels(context, pl)
        val builder = NotificationCompat.Builder(context, if (toSettle) DEBTS else REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openEvent(context, id, bandId, eventId, toSettle))
            .setAutoCancel(true)
        // One line per person owed in a debt reminder.
        if ('\n' in text) builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openEvent(context, id, band.id, event.id))
            .setAutoCancel(true)
            .build())
    }

    /** Opens the app at the event (at its To settle section with [toSettle]); without one (a test reminder), just the app. */
    private fun openEvent(context: Context, requestCode: Int, bandId: String?, eventId: String?, toSettle: Boolean = false): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (bandId != null && eventId != null) intent.putExtra(EXTRA_BAND, bandId).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_TO_SETTLE, toSettle)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
