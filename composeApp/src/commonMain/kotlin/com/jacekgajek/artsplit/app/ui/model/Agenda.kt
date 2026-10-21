package com.jacekgajek.artsplit.app.ui.model

import androidx.compose.runtime.Composable
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.agenda_arrival
import com.jacekgajek.artsplit.app.generated.agenda_concert_start
import com.jacekgajek.artsplit.model.AgendaKind
import com.jacekgajek.artsplit.model.AgendaTemplateItem
import com.jacekgajek.artsplit.model.AgendaTime
import com.jacekgajek.artsplit.model.Event
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
