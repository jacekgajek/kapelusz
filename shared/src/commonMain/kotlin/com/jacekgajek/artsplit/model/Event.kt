package com.jacekgajek.artsplit.model

import kotlinx.datetime.LocalDate
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
    /** Epoch millis of the last operation touching this event. */
    val updatedAt: Long = 0,
) {
    fun participant(partyId: Id): Participant? = participants.firstOrNull { it.partyId == partyId }
    fun guest(id: Id): Guest? = guests.firstOrNull { it.id == id }
    fun refundOf(costId: Id): Refund? = refunds.firstOrNull { it.costId == costId }
    val income: Money get() = holdings.sumOfMoney { it.amount }
}

@Serializable
data class Guest(val id: Id, val name: String)

/** A member or guest taking part in an event. [weight] is a share in percent: 100 = one normal share. */
@Serializable
data class Participant(val partyId: Id, val weight: Int = DEFAULT_WEIGHT)

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
)

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
