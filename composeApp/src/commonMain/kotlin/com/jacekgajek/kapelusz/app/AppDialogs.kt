package com.jacekgajek.kapelusz.app

import com.jacekgajek.kapelusz.app.ui.screens.event.rememberPlaceLookup
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.generated.map_reading
import com.jacekgajek.kapelusz.app.generated.map_unreadable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.AppSettings
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.today
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_was_deleted
import com.jacekgajek.kapelusz.app.generated.cancel
import com.jacekgajek.kapelusz.app.generated.debt_prompt
import com.jacekgajek.kapelusz.app.generated.lost_access
import com.jacekgajek.kapelusz.app.generated.ok
import com.jacekgajek.kapelusz.app.generated.place_new_event
import com.jacekgajek.kapelusz.app.generated.place_nothing
import com.jacekgajek.kapelusz.app.generated.place_title
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_added
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_not_now
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_playing
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_question
import com.jacekgajek.kapelusz.app.generated.reminder_prompt_yes
import com.jacekgajek.kapelusz.app.notify.NotificationRules
import com.jacekgajek.kapelusz.app.platform.rememberNotificationPermission
import com.jacekgajek.kapelusz.app.sync.LostBand
import com.jacekgajek.kapelusz.app.ui.components.labelRes
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.Can
import com.jacekgajek.kapelusz.app.ui.model.formatDate
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.UpdateEvent
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
 * A place shared from Google Maps (Android, ITERATION_2 §4.4): "Set the location of…" one of the upcoming events the
 * user may edit (nearest first), or "New event at this place". The location becomes what the server reads from the
 * link (ITERATION_5 §9.1): the place's name and address, or the pin's coordinates; failing that, the name Maps shared.
 */
@Composable
internal fun SharedPlaceDialog(graph: AppGraph, state: BandState.Ready, onOpenEvent: (String) -> Unit, onNewEvent: () -> Unit) {
    val place by graph.pendingSharedPlace.collectAsState()
    val shared = place ?: return
    val band = state.band
    val can = Can(band, state.userId)
    val events = band.events.filter { !it.archived && it.date >= today() && can.editEvent(it) }.sortedBy { it.date }
    val dismiss = { graph.pendingSharedPlace.value = null }
    var rejected by remember(shared) { mutableStateOf<RejectionReason?>(null) }
    var reading by remember(shared) { mutableStateOf(false) }
    var unreadable by remember(shared) { mutableStateOf(false) }
    val places = rememberPlaceLookup(graph, band, state.userId)
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.place_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shared.name?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                rejected?.let { Text(stringResource(it.labelRes()), color = MaterialTheme.colorScheme.error) }
                if (reading) Text(stringResource(Res.string.map_reading), style = MaterialTheme.typography.bodySmall)
                if (unreadable) Text(stringResource(Res.string.map_unreadable), color = MaterialTheme.colorScheme.error)
                if (events.isEmpty() && !can.createEvent()) Text(stringResource(Res.string.place_nothing))
                events.forEach { event ->
                    TextButton(enabled = !reading, onClick = {
                        scope.launch {
                            reading = true
                            val text = places?.let { runSuspendCatching { it.resolve(shared.url) }.getOrNull() } ?: shared.name
                            reading = false
                            // Another place shared meanwhile: this one is no longer what the dialog offers.
                            if (graph.pendingSharedPlace.value != shared) return@launch
                            unreadable = text == null
                            if (text == null) return@launch
                            val rejection = graph.dispatch(UpdateEvent(event.id, location = Change(text)))
                            rejected = rejection?.reason
                            if (rejection == null) {
                                dismiss()
                                onOpenEvent(event.id)
                            }
                        }
                    }) { Text("${event.name} · ${formatDate(event.date, language)}") }
                }
                if (can.createEvent()) {
                    TextButton(enabled = !reading, onClick = {
                        graph.pendingNewEventPlace.value = shared
                        dismiss()
                        onNewEvent()
                    }) { Text(stringResource(Res.string.place_new_event)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.cancel)) } },
    )
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
