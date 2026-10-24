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

    /** May be in a pair at all: on the roster, with an account (to answer), and taking part in events. */
    fun canPair(member: Member): Boolean = !member.archived && member.userId != null && member.role != Role.VIEWER

    /** May send an invitation: as [canPair], from members up (artists only answer one). */
    fun canInvite(member: Member): Boolean = canPair(member) && member.role.atLeast(Role.MEMBER)

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

    /** A pair is two of these, never two artists: someone in it records what the pair hands over (§7). */
    private fun validPair(a: Member, b: Member) =
        canPair(a) && canPair(b) && a.settlesWith == b.id && b.settlesWith == a.id && !(a.role == Role.ARTIST && b.role == Role.ARTIST)

    /**
     * [band] without the pairs and invitations a change made impossible (§7): someone archived, unlinked or made a
     * viewer, two artists left together, or an inviter who may no longer invite. Nothing else changes.
     */
    fun tidy(band: Band): Band {
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
        return if (members == band.members) band else band.copy(members = members)
    }

    /** Events not settled yet where [a] and [b] both play: ending their pair splits them up again there. */
    fun openEventsTogether(band: Band, a: Id, b: Id): Int = band.events.count { event ->
        !event.archived && event.participant(a) != null && event.participant(b) != null &&
            SettlementCalculator.settle(band, event).status != EventStatus.SETTLED
    }

    /** The pairs settling together in [event]: both partners take part. Each member maps to the other. */
    fun partners(band: Band, event: Event): Map<Id, Id> = band.members
        .filter { member -> member.settlesWith != null && event.participant(member.id) != null && event.participant(member.settlesWith!!) != null }
        .associate { it.id to it.settlesWith!! }

    /**
     * What recording [amount] from [from] to [to] (parties of a suggestion, either partner standing for a pair) records
     * between real people (ITERATION_5 §7.3):
     * - a pair passing money on: each pays out of what they hold, the bigger holder first (anything over that is the
     *   bigger holder's);
     * - a pair receiving: the one owed more gets it, but an artist no more than they are owed, the rest going to the
     *   partner;
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
                val byHolding = senders.sortedByDescending { line(it)?.balance?.minor ?: 0 }
                var left = amount.minor
                val parts = byHolding.map { id ->
                    val part = minOf(left, (line(id)?.balance?.minor ?: 0).coerceAtLeast(0))
                    left -= part
                    id to part
                }.toMutableList()
                parts[0] = parts[0].first to parts[0].second + left
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
