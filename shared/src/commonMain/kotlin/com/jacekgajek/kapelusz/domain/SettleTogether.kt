package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Role

/**
 * Two members settling together (ITERATION_5 §7): in an event where both take part they are one party on Settlement
 * and in its transfers; everywhere else, what they collected, hold, spent and earned stays their own.
 */
object SettleTogether {

    /**
     * May be in a pair at all: on the roster, with an account (to answer), from artists up (ITERATION_6 §11). Not viewers,
     * who play in no event. An artist still holds no money: their partner keeps or receives their share.
     */
    fun canPair(member: Member): Boolean = !member.archived && member.userId != null && member.role.atLeast(Role.ARTIST)

    /** May send an invitation: as [canPair]. */
    fun canInvite(member: Member): Boolean = canPair(member)

    /** In a pair, or with an invitation sent or received. */
    fun isTaken(band: Band, memberId: Id): Boolean {
        val member = band.member(memberId) ?: return false
        return member.settlesWith != null || member.invitedToSettle != null || band.members.any { it.invitedToSettle == memberId }
    }

    /** Who invited [memberId] to settle together, if anyone. */
    fun inviterOf(band: Band, memberId: Id): Member? = band.members.firstOrNull { it.invitedToSettle == memberId }

    /** [from] may invite [to] now. */
    fun mayInvite(band: Band, from: Member, to: Member): Boolean =
        from.id != to.id && canInvite(from) && canPair(to) && !isTaken(band, from.id) && !isTaken(band, to.id)

    private fun validPair(a: Member, b: Member) = canPair(a) && canPair(b) && a.settlesWith == b.id && b.settlesWith == a.id

    /**
     * [band] without the pairs and invitations a change made impossible (§7): someone archived, unlinked, or made a
     * viewer. Nothing else changes. A pair ending keeps the events it settled together as they were
     * [before] the change, while it still counted.
     */
    fun tidy(band: Band, before: Band = band): Band {
        val byId = band.members.associateBy { it.id }
        val members = band.members.map { member ->
            val partner = member.settlesWith?.let { byId[it] }
            val keepsPair = partner != null && validPair(member, partner)
            val invitee = member.invitedToSettle?.let { byId[it] }
            val keepsInvite = invitee != null && invitee.id != member.id && canInvite(member) && canPair(invitee) &&
                member.settlesWith == null && invitee.settlesWith == null && invitee.invitedToSettle == null &&
                band.members.count { it.invitedToSettle == invitee.id } == 1 && band.members.none { it.invitedToSettle == member.id }
            if (keepsPair == (member.settlesWith != null) && keepsInvite == (member.invitedToSettle != null)) member
            else member.copy(settlesWith = member.settlesWith.takeIf { keepsPair }, invitedToSettle = member.invitedToSettle.takeIf { keepsInvite })
        }
        if (members == band.members) return band
        val ended = band.members.filter { it.settlesWith != null && members.first { m -> m.id == it.id }.settlesWith == null }
        return ended.fold(band) { result, member -> keepSettled(result, member.id, member.settlesWith!!, before) }.copy(members = members)
    }

    /**
     * Before the pair of [a] and [b] ends: the events they settled together keep them together (ITERATION_5 §7), so ending
     * a pair never reopens a settled event.
     */
    fun keepSettled(band: Band, a: Id, b: Id, before: Band = band): Band {
        val pair = listOf(a, b).sorted()
        val settled = before.events
            .filter { event -> partners(before, event)[a] == b && SettlementCalculator.settle(before, event).status == EventStatus.SETTLED }
            .map { it.id }.toSet()
        return band.copy(
            events = band.events.map { event ->
                if (event.id !in settled || pair in event.settledTogether) event
                else event.copy(settledTogether = event.settledTogether + listOf(pair))
            },
        )
    }

    /** Events not settled yet where [a] and [b] both play: ending their pair splits them up again there. */
    fun openEventsTogether(band: Band, a: Id, b: Id): Int = band.events.count { event ->
        !event.archived && event.participant(a) != null && event.participant(b) != null &&
            SettlementCalculator.settle(band, event).status != EventStatus.SETTLED
    }

    /** The pairs settling together in [event]: both partners take part. Each member maps to the other. */
    fun partners(band: Band, event: Event): Map<Id, Id> {
        // A pair kept from before comes first: its members may be in another pair by now.
        val kept = event.settledTogether
            .filter { pair -> pair.all { event.participant(it) != null } }
            .flatMap { (a, b) -> listOf(a to b, b to a) }.toMap()
        // Only pairs that may still be (a stored pair with a viewer, say, counts no more).
        val current = band.members
            .filter { member -> member.settlesWith != null && event.participant(member.id) != null && event.participant(member.settlesWith!!) != null }
            .filter { member -> band.member(member.settlesWith!!)?.let { validPair(member, it) } == true }
            .filter { it.id !in kept && it.settlesWith !in kept }
            .associate { it.id to it.settlesWith!! }
        return kept + current
    }

    /**
     * Of what [memberId] is still owed in [settlement], the part their partner holds for them (ITERATION_6 §11): what the
     * partner holds over their own target, up to what [memberId] is owed. Zero without a partner here.
     */
    fun keptByPartner(settlement: Settlement, memberId: Id): Money {
        val owed = settlement.line(memberId)?.delta?.takeIf { it.isNegative }?.let { -it } ?: return Money.ZERO
        val spare = settlement.partners[memberId]?.let { settlement.line(it) }?.delta?.takeIf { it.isPositive } ?: return Money.ZERO
        return minOf(owed, spare)
    }

    /**
     * What recording [amount] from [from] to [to] (parties of a suggestion, either partner standing for a pair) records
     * between real people (ITERATION_5 §7.3):
     * - a pair passing money on: each pays out of what they hold, the bigger holder first (anything over that is the
     *   bigger holder's);
     * - a pair receiving: the one owed more gets it;
     * - money between the band fund and a pair with the treasurer: always the treasurer, who holds the fund.
     */
    fun split(band: Band, settlement: Settlement, from: Id, to: Id, amount: Money): List<SuggestedTransfer> {
        val order = settlement.lines.withIndex().associate { (i, line) -> line.partyId to i }
        fun side(partyId: Id) = listOfNotNull(partyId, settlement.partners[partyId]).sortedBy { order[it] ?: Int.MAX_VALUE }
        fun line(partyId: Id) = settlement.line(partyId)
        val treasurer = band.treasurerMemberId

        val senders = side(from)
        val paid: List<Pair<Id, Long>> = when {
            senders.size == 1 -> listOf(from to amount.minor)
            to == BAND_FUND && treasurer in senders -> listOf(treasurer!! to amount.minor)
            else -> {
                // An artist holds no money (ITERATION_4 §1.1): only in a pair kept by an event settled before one of them
                // became an artist (Event.settledTogether), and only if that event is reopened.
                fun holds(id: Id) = if (Artists.isArtist(band, id)) 0 else (line(id)?.balance?.minor ?: 0).coerceAtLeast(0)
                val byHolding = senders.sortedByDescending { holds(it) }
                var left = amount.minor
                val parts = byHolding.map { id ->
                    val part = minOf(left, holds(id))
                    left -= part
                    id to part
                }.toMutableList()
                val payer = parts.indexOfFirst { !Artists.isArtist(band, it.first) }.coerceAtLeast(0)
                parts[payer] = parts[payer].first to parts[payer].second + left
                parts
            }
        }
        val receivers = side(to)
        val received: List<Pair<Id, Long>> = when {
            receivers.size == 1 -> listOf(to to amount.minor)
            from == BAND_FUND && treasurer in receivers -> listOf(treasurer!! to amount.minor)
            else -> {
                val byOwed = receivers.sortedBy { line(it)?.delta?.minor ?: 0 }
                val first = byOwed[0]
                val cap = if (Artists.isArtist(band, first)) (-(line(first)?.delta?.minor ?: 0)).coerceAtLeast(0) else amount.minor
                val part = minOf(cap, amount.minor)
                listOf(first to part, byOwed[1] to amount.minor - part)
            }
        }
        // Matches the two sides in order, like two queues: each step moves as much as both still have.
        val result = mutableListOf<SuggestedTransfer>()
        val pay = paid.filter { it.second > 0 }.map { it.first to it.second }.toMutableList()
        val get = received.filter { it.second > 0 }.map { it.first to it.second }.toMutableList()
        while (pay.isNotEmpty() && get.isNotEmpty()) {
            val (payer, payLeft) = pay[0]
            val (getter, getLeft) = get[0]
            val moved = minOf(payLeft, getLeft)
            result += SuggestedTransfer(payer, getter, Money(moved))
            if (payLeft == moved) pay.removeAt(0) else pay[0] = payer to payLeft - moved
            if (getLeft == moved) get.removeAt(0) else get[0] = getter to getLeft - moved
        }
        return result
    }
}
