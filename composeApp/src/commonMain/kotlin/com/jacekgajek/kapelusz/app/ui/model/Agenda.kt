package com.jacekgajek.kapelusz.app.ui.model

import androidx.compose.runtime.Composable
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.agenda_arrival
import com.jacekgajek.kapelusz.app.generated.agenda_concert_start
import com.jacekgajek.kapelusz.app.generated.agenda_sound_check
import com.jacekgajek.kapelusz.model.AgendaKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Event
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/** The label of an agenda time: the built-in one in the user's language, or the custom label. */
@Composable
fun AgendaTime.displayLabel(): String = kind?.label() ?: label.orEmpty()

@Composable
fun AgendaTemplateItem.displayLabel(): String = kind?.label() ?: label.orEmpty()

@Composable
fun AgendaKind.label(): String = stringResource(
    when (this) {
        AgendaKind.ARRIVAL -> Res.string.agenda_arrival
        AgendaKind.SOUND_CHECK -> Res.string.agenda_sound_check
        AgendaKind.CONCERT_START -> Res.string.agenda_concert_start
    },
)

/** `17:00`, or `01:30 (+1)` for a time after midnight. 24-hour clock in both languages. */
fun formatAgendaTime(item: AgendaTime): String = item.time?.let { formatTime(it) + if (item.isNextDay) " (+1)" else "" }.orEmpty()

fun formatTime(time: LocalTime): String = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

/** The set times of an event in the order of the day (after-midnight times last). */
fun Event.setTimes(): List<AgendaTime> = agenda.filter { it.time != null }.sortedBy { it.minutesOfEventDay }

/** "Arrival 17:00 · Concert start 20:00", or null when no time is set. */
@Composable
fun Event.agendaLine(): String? = setTimes().takeIf { it.isNotEmpty() }?.map { "${it.displayLabel()} ${formatAgendaTime(it)}" }?.joinToString(" · ")

/** An entry in the agenda template editor: the built-in [kind] it started as (if any) and the text shown. */
data class TemplateDraft(val kind: AgendaKind?, val text: String)

/**
 * Back to a template entry: a built-in one stays built-in (shown in each user's language) as long as its text is
 * untouched; edited, it becomes a custom label. Entries keep their kind when moved or when others are removed.
 */
fun TemplateDraft.toItem(builtInLabel: (AgendaKind) -> String): AgendaTemplateItem = if (kind != null && text.trim() == builtInLabel(kind)) AgendaTemplateItem(kind) else AgendaTemplateItem(label = text.trim())
