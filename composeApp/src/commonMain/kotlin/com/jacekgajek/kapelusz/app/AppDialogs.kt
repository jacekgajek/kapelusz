package com.jacekgajek.kapelusz.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_was_deleted
import com.jacekgajek.kapelusz.app.generated.debt_prompt
import com.jacekgajek.kapelusz.app.generated.lost_access
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_added
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_not_now
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_playing
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_question
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_yes
import com.jacekgajek.kapelusz.app.notify.NotificationRules
import com.jacekgajek.kapelusz.app.platform.rememberNotificationPermission
import com.jacekgajek.kapelusz.app.sync.LostBand
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A dialog, not a snackbar: losing the open band leaves its screens, so they cannot show the message. */
@Composable
internal fun LostAccessDialog(graph: AppGraph) {
    var lostBand by remember { mutableStateOf<LostBand?>(null) }
    LaunchedEffect(graph) { graph.sync.lostAccess.collect { lostBand = it } }
    lostBand?.let { lost ->
        AlertDialog(
            onDismissRequest = { lostBand = null },
            text = { Text(stringResource(if (lost.deleted) Res.string.band_was_deleted else Res.string.lost_access, lost.name)) },
            confirmButton = { TextButton(onClick = { lostBand = null }) { Text(stringResource(Res.string.ok)) } },
        )
    }
}

/**
 * Asks once, when the first upcoming gig of the user appears, whether they want reminders, naming who added them
 * (ITERATION_2 §5.2). Android 13+ only; elsewhere no permission is needed or there are no notifications. Gig reminders
 * start off (ITERATION_4 §1.3), so this is how they are turned on; the permission it asks for also lets the debt
 * reminders through.
 */
@Composable
internal fun ReminderPrompt(graph: AppGraph, settings: AppSettings) {
    val permission = rememberNotificationPermission() ?: return
    if (settings.notificationPromptDone || !permission.needsAsking) return
    val userId = graph.sessions.session.collectAsState().value?.userId ?: return
    val bandState by graph.bands.state.collectAsState()
    val (band, event) = remember(bandState) { NotificationRules.firstUpcomingGig(graph.bands.allBands(), userId, today()) } ?: return
    val me = band.memberOfUser(userId) ?: return
    val adder = event.participant(me.id)?.addedBy?.takeIf { it != me.id }?.let { band.member(it)?.name }
    val days = settings.reminderDays.minOrNull() ?: 1
    val date = formatDate(event.date, language)
    val text = if (adder != null) stringResource(Res.string.reminder_prompt_added, adder, event.name, date)
    else stringResource(Res.string.reminder_prompt_playing, event.name, date)
    AlertDialog(
        onDismissRequest = {},
        text = { Text(text + " " + pluralStringResource(Res.plurals.reminder_prompt_question, days, days)) },
        confirmButton = {
            TextButton(onClick = {
                // Answered now, so a screen rotation during Android's dialog cannot bring the prompt back.
                // The permission covers the debt reminders too: no second question about them (V5-M1 D3).
                graph.settings.update { it.copy(notificationPromptDone = true, debtPromptDone = true) }
                permission.request { granted -> graph.settings.update { it.copy(remindersEnabled = granted) } }
            }) { Text(stringResource(Res.string.reminder_prompt_yes)) }
        },
        dismissButton = {
            TextButton(onClick = { graph.settings.update { it.copy(notificationPromptDone = true, debtPromptDone = true, remindersEnabled = false) } }) {
                Text(stringResource(Res.string.reminder_prompt_not_now))
            }
        },
    )
}

/**
 * Debt reminders are on by default (ITERATION_4 §1.3), but on Android 13+ they show only with the notification
 * permission, which the gig prompt asks for only with a gig of one's own coming. So once the user has money to pass on
 * and the permission wasn't given, asks once (V5-M1 D3).
 */
@Composable
internal fun DebtReminderPrompt(graph: AppGraph, settings: AppSettings) {
    val permission = rememberNotificationPermission() ?: return
    if (settings.debtPromptDone || !settings.debtReminders || !permission.needsAsking) return
    val userId = graph.sessions.session.collectAsState().value?.userId ?: return
    val bandState by graph.bands.state.collectAsState()
    val first = remember(bandState, settings.notificationPromptDone) {
        val bands = graph.bands.allBands()
        // Not over the gig prompt: that one asks for the same permission first.
        if (!settings.notificationPromptDone && NotificationRules.firstUpcomingGig(bands, userId, today()) != null) return@remember null
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        NotificationRules.debtReminders(bands, userId, settings.reminderTime, now).firstOrNull()
    } ?: return
    AlertDialog(
        onDismissRequest = {},
        text = { Text(stringResource(Res.string.debt_prompt, first.eventName)) },
        confirmButton = {
            TextButton(onClick = {
                graph.settings.update { it.copy(debtPromptDone = true) }
                permission.request {}
            }) { Text(stringResource(Res.string.reminder_prompt_yes)) }
        },
        dismissButton = {
            TextButton(onClick = { graph.settings.update { it.copy(debtPromptDone = true) } }) { Text(stringResource(Res.string.reminder_prompt_not_now)) }
        },
    )
}
