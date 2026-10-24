package com.jacekgajek.kapelusz.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

const val DEFAULT_WEIGHT = 100

@Serializable
data class Event(
    val id: Id,
    val name: String,
    val date: LocalDate,
    val location: String? = null,
    val notes: String? = null,
    val archived: Boolean = false,
    /** Ad-hoc people who exist only in this event. */
    val guests: List<Guest> = emptyList(),
    val participants: List<Participant> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    val refunds: List<Refund> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    /** Positive: taken off the top for the band fund. Negative: the fund covers part of the event. */
    val fundAmount: Money = Money.ZERO,
    /** Agenda times of the day (ITERATION_2 §4.1), copied from the band's template when the event is created. */
    val agenda: List<AgendaTime> = emptyList(),
    /** Files about the gig, not tied to any money: contract, rider, stage plan (ITERATION_4 §2.1). */
    val attachments: List<Attachment> = emptyList(),
    /** Epoch millis of the last operation touching this event. */
    val updatedAt: Long = 0,
    /**
     * Pairs that settled this event together and have ended since (ITERATION_5 §7): here they stay one party, so the
     * event stays settled. Each is the two member ids.
     */
    val settledTogether: List<List<Id>> = emptyList(),
) {
    fun participant(partyId: Id): Participant? = participants.firstOrNull { it.partyId == partyId }
    fun guest(id: Id): Guest? = guests.firstOrNull { it.id == id }
    fun refundOf(costId: Id): Refund? = refunds.firstOrNull { it.costId == costId }
    val income: Money get() = holdings.sumOfMoney { it.amount }

    /** Part of [income] the organiser still owes (ITERATION_4 §2.2). */
    val awaited: Money get() = holdings.filter { it.awaited }.sumOfMoney { it.amount }
}

@Serializable
data class Guest(val id: Id, val name: String)

/**
 * A member or guest taking part in an event. [weight] is a share in percent: 100 = one normal share. [addedBy] is the
 * member who added them (unknown for events from before version 2). With a [fixedShare] they take exactly that amount
 * off the top instead of a share by weight (ITERATION_3 §2.4); the weight is kept for switching back.
 */
@Serializable
data class Participant(val partyId: Id, val weight: Int = DEFAULT_WEIGHT, val addedBy: Id? = null, val fixedShare: Money? = null)

/** The built-in agenda times; shown in the user's language as long as nobody renamed them. */
@Serializable
enum class AgendaKind { ARRIVAL, SOUND_CHECK, CONCERT_START }

/**
 * One agenda time of an event: a built-in [kind] or a custom [label], and the [time] once set. Times before
 * [AgendaTime.NEXT_DAY_BEFORE_HOUR] are on the next day (a gig ending after midnight).
 */
@Serializable
data class AgendaTime(
    val id: Id,
    val kind: AgendaKind? = null,
    val label: String? = null,
    val time: LocalTime? = null,
    /** Copied from the band's template: removing its time keeps it (unset) instead of deleting it. */
    val fromTemplate: Boolean = false,
) {
    /** Minutes from the event day's midnight, counting early-morning times as the next day. */
    val minutesOfEventDay: Int? get() = time?.let { it.hour * 60 + it.minute + if (it.hour < NEXT_DAY_BEFORE_HOUR) 24 * 60 else 0 }

    val isNextDay: Boolean get() = time != null && time.hour < NEXT_DAY_BEFORE_HOUR

    companion object {
        const val NEXT_DAY_BEFORE_HOUR = 5
        const val MAX_LABEL = 40
        const val MAX_TIMES = 12
        const val MAX_ID = 64
    }
}

/** An entry of the band's agenda template: a built-in [kind] or a custom [label]. */
@Serializable
data class AgendaTemplateItem(val kind: AgendaKind? = null, val label: String? = null)

@Serializable
enum class IncomeSource { TICKETS_ONLINE, DOOR, HAT, MERCH, CONTRACT, SPONSORSHIP, OWN_CONTRIBUTION, OTHER }

/** Money a participant collected for the event. Event income is the sum of holdings. */
@Serializable
data class Holding(
    val id: Id,
    val partyId: Id,
    val amount: Money,
    val source: IncomeSource,
    val note: String? = null,
    /** E.g. the contract this money was paid under (ITERATION_4 §2.1). */
    val attachments: List<Attachment> = emptyList(),
    /** Who recorded it; for an awaited fee ([ORGANISER]), the lead reminded to chase it (ITERATION_4 §2.2). */
    val recordedBy: Id? = null,
) {
    val awaited: Boolean get() = partyId == ORGANISER
}

/** Part of a cost repaid out of this event's income. At most one per cost per event. */
@Serializable
data class Refund(val costId: Id, val amount: Money)

/** Money handed from one party to another; [from]/[to] are participant ids or [BAND_FUND]. */
@Serializable
data class Transfer(
    val id: Id,
    val from: Id,
    val to: Id,
    val amount: Money,
    val note: String? = null,
    val recordedAt: Long = 0,
    val recordedBy: Id? = null,
)
