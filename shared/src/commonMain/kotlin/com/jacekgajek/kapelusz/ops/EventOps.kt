package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.AgendaTime
import com.jacekgajek.kapelusz.model.FundCut
import com.jacekgajek.kapelusz.model.Guest
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Participant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// An event as a whole: creating, editing, archiving and deleting it, and its agenda.

@Serializable
@SerialName("CreateEvent")
data class CreateEvent(
    override val eventId: Id,
    val name: String,
    val date: LocalDate,
    val location: String? = null,
    val notes: String? = null,
    val guests: List<Guest> = emptyList(),
    val participants: List<Participant> = emptyList(),
    /** The band's agenda template at creation time, times not set yet (ITERATION_2 §4.1). */
    val agenda: List<AgendaTime> = emptyList(),
    /** The band's fund default at creation time (ITERATION_7 §5.2); null: nothing to the fund. */
    val fund: FundCut? = null,
) : EventOp

/** Replaces an event's agenda times (lead+); last write wins, like other event details. */
@Serializable
@SerialName("SetEventAgenda")
data class SetEventAgenda(override val eventId: Id, val agenda: List<AgendaTime>) : EventOp

@Serializable
@SerialName("UpdateEvent")
data class UpdateEvent(
    override val eventId: Id,
    val name: Change<String>? = null,
    val date: Change<LocalDate>? = null,
    val location: Change<String?>? = null,
    val notes: Change<String?>? = null,
) : EventOp

@Serializable
@SerialName("SetEventArchived")
data class SetEventArchived(override val eventId: Id, val archived: Boolean) : EventOp

@Serializable
@SerialName("DeleteEvent")
data class DeleteEvent(override val eventId: Id) : EventOp
