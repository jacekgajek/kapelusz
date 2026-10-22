package com.jacekgajek.kapelusz.ops

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Money

/**
 * Limits on everything that comes into a band (ITERATION_3 R1), checked after every operation on the band it
 * produced, so client and server, and undo, enforce the same. Only what the operation changed is checked: data from
 * before the limits keeps working.
 */
object Limits {
    /** 1 000 000 000.00 in the band's currency: far above any gig, and safe from overflow in the settlement's sums. */
    const val MAX_AMOUNT = 100_000_000_000L
    const val MAX_NAME = 100
    const val MAX_TEXT = 500
    const val MAX_NOTES = 2000
    const val MAX_MEMBERS = 200
    const val MAX_EVENTS = 2000
    const val MAX_COSTS = 5000
    const val MAX_FUND_ENTRIES = 5000
    const val MAX_PARTIES_PER_EVENT = 200
    const val MAX_ITEMS_PER_EVENT = 500

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** Why [after] breaks a limit that [before] did not; null when it keeps to them. */
    fun violation(before: Band, after: Band): Rejection? {
        if (after.name != before.name) text(after.name, MAX_NAME, "Band name")?.let { return it }
        count(before.members.size, after.members.size, MAX_MEMBERS, "members")?.let { return it }
        count(before.events.size, after.events.size, MAX_EVENTS, "events")?.let { return it }
        count(before.costs.size, after.costs.size, MAX_COSTS, "costs")?.let { return it }
        count(before.fundEntries.size, after.fundEntries.size, MAX_FUND_ENTRIES, "band fund entries")?.let { return it }

        val oldMembers = before.members.associateBy { it.id }
        val guestIds = after.events.flatMapTo(mutableSetOf()) { event -> event.guests.map { it.id } }
        for (member in after.members) {
            if (oldMembers[member.id] == member) continue
            id(member.id, "member")?.let { return it }
            text(member.name, MAX_NAME, "Member name")?.let { return it }
            // A member's id must not be a guest's: the guest's money would become theirs.
            if (oldMembers[member.id] == null && member.id in guestIds) return invalid("Id ${member.id} is a guest's")
        }
        val oldEvents = before.events.associateBy { it.id }
        val memberIds = after.members.mapTo(mutableSetOf()) { it.id }
        for (event in after.events) {
            val old = oldEvents[event.id]
            if (old == event) continue
            id(event.id, "event")?.let { return it }
            text(event.name, MAX_NAME, "Event name")?.let { return it }
            event.location?.let { text(it, MAX_TEXT, "Location")?.let { r -> return r } }
            event.notes?.let { text(it, MAX_NOTES, "Notes")?.let { r -> return r } }
            amount(event.fundAmount.abs(), "Band fund amount")?.let { return it }
            count(old?.let { it.participants.size + it.guests.size } ?: 0, event.participants.size + event.guests.size, MAX_PARTIES_PER_EVENT, "participants")
                ?.let { return it }
            for (guest in event.guests) {
                if (old?.guest(guest.id) == guest) continue
                id(guest.id, "guest")?.let { return it }
                text(guest.name, MAX_NAME, "Guest name")?.let { return it }
                if (old?.guest(guest.id) == null && guest.id in memberIds) return invalid("Id ${guest.id} is a member's")
            }
            count(old?.holdings?.size ?: 0, event.holdings.size, MAX_ITEMS_PER_EVENT, "collected amounts")?.let { return it }
            count(old?.transfers?.size ?: 0, event.transfers.size, MAX_ITEMS_PER_EVENT, "transfers")?.let { return it }
            val oldHoldings = old?.holdings.orEmpty().associateBy { it.id }
            for (holding in event.holdings) {
                if (oldHoldings[holding.id] == holding) continue
                id(holding.id, "collected amount")?.let { return it }
                amount(holding.amount, "Collected amount")?.let { return it }
                holding.note?.let { text(it, MAX_TEXT, "Note")?.let { r -> return r } }
            }
            val oldTransfers = old?.transfers.orEmpty().associateBy { it.id }
            for (transfer in event.transfers) {
                if (oldTransfers[transfer.id] == transfer) continue
                // Settle all names its transfers after the operation ("<opId>-<n>"), so they are ids as well.
                id(transfer.id, "transfer")?.let { return it }
                amount(transfer.amount, "Transfer")?.let { return it }
                transfer.note?.let { text(it, MAX_TEXT, "Note")?.let { r -> return r } }
            }
            for (refund in event.refunds) amount(refund.amount, "Refund")?.let { return it }
        }
        val oldCosts = before.costs.associateBy { it.id }
        for (cost in after.costs) {
            if (oldCosts[cost.id] == cost) continue
            id(cost.id, "cost")?.let { return it }
            amount(cost.amount, "Cost")?.let { return it }
            cost.description?.let { text(it, MAX_TEXT, "Description")?.let { r -> return r } }
        }
        val oldEntries = before.fundEntries.associateBy { it.id }
        for (entry in after.fundEntries) {
            if (oldEntries[entry.id] == entry) continue
            id(entry.id, "band fund entry")?.let { return it }
            text(entry.description, MAX_TEXT, "Description")?.let { return it }
            amount(entry.amount.abs(), "Band fund entry")?.let { return it }
        }
        return null
    }

    private fun id(value: Id, what: String): Rejection? = when {
        value == BAND_FUND -> invalid("\"$BAND_FUND\" is reserved for the band fund, not a $what")
        !ID.matches(value) -> invalid("Invalid $what id")
        else -> null
    }

    private fun text(value: String, max: Int, what: String): Rejection? =
        if (value.length > max) invalid("$what is longer than $max characters") else null

    private fun amount(value: Money, what: String): Rejection? =
        if (value.minor > MAX_AMOUNT) Rejection(RejectionCode.INVALID, "$what is too large", RejectionReason.INVALID_AMOUNT) else null

    /** Only growth past a limit counts: a band above it from before can still shrink. */
    private fun count(before: Int, after: Int, max: Int, what: String): Rejection? =
        if (after > max && after > before) Rejection(RejectionCode.CONFLICT, "At most $max $what", RejectionReason.LIMIT_REACHED) else null

    private fun invalid(message: String) = Rejection(RejectionCode.INVALID, message, RejectionReason.INVALID_INPUT)

    private fun Money.abs(): Money = if (isNegative) -this else this
}
