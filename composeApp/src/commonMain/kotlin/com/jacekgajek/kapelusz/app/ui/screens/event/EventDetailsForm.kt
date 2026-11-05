package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jacekgajek.kapelusz.app.util.newId
import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.effectiveAgendaTemplate
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.SetEventAgenda
import com.jacekgajek.kapelusz.ops.UpdateEvent

/**
 * The "When and where" of an event, edited: name, date, location and the agenda's times. [opened] is the event as it
 * was when the form opened, so that Save sends only what the user changed. An event from before agenda times opens
 * with [band]'s template, all unset.
 */
@Stable
class EventDetailsForm(val opened: Event, band: Band) {
    var fields by mutableStateOf(
        EventFieldsState(
            name = opened.name,
            date = opened.date,
            location = opened.location.orEmpty(),
            agenda = opened.agenda.ifEmpty { fromTemplate(band) },
        ),
    )

    val ready: Boolean by derivedStateOf { fields.name.isNotBlank() }

    /** The band's template, all unset: for an event from before agenda times. */
    private fun fromTemplate(band: Band): List<AgendaTime> {
        val template = band.effectiveAgendaTemplate
        return template.map { AgendaTime(newId(), it.kind, it.label, fromTemplate = true) }
    }

    /**
     * What Save sends: the details that changed, then the agenda if it did. Template rows that got no time are not a
     * change to an event that had no agenda.
     */
    fun operations(): List<Operation> {
        val update = UpdateEvent(
            opened.id,
            name = Change(fields.name).takeIf { fields.name.trim() != opened.name },
            date = Change(fields.date).takeIf { fields.date != opened.date },
            location = Change(fields.location.ifBlank { null }).takeIf { fields.location.trim() != opened.location.orEmpty() },
        )
        val agenda = fields.agenda
        val agendaChanged = agenda != opened.agenda && !(opened.agenda.isEmpty() && agenda.all { it.time == null })
        return listOfNotNull(
            update.takeIf { listOf(it.name, it.date, it.location).any { change -> change != null } },
            SetEventAgenda(opened.id, agenda).takeIf { agendaChanged },
        )
    }

    /**
     * Runs [operations] in order with [run] (true when it went through); stops at the first refusal, which keeps the
     * dialog open with what the user typed. True when all went through. Only when [ready].
     */
    fun save(run: (Operation) -> Boolean): Boolean {
        check(ready) { "Save is offered only when the form is ready" }
        return operations().all { run(it) }
    }
}
