package com.jacekgajek.kapelusz.settlement

import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Participant

/** A member's default share and the events it may be applied to (I4 §2.3). */
object MemberShares {

    /** The same share: the same fixed amount, the same percentage, or (both by weight) the same weight. */
    fun isDefault(member: Member, participant: Participant): Boolean = when {
        member.shareFixed != null -> participant.fixedShare == member.shareFixed
        member.sharePercent != null -> participant.percent == member.sharePercent
        else -> participant.byWeight && participant.weight == member.shareWeight
    }

    /** Events a changed default can still be applied to: not archived, not settled, the member in it. */
    fun canApply(view: SettledEvents, event: Event, member: Member): Boolean = !event.archived && event.participant(member.id) != null && !view.isSettled(event)

    /** Events where the member still has their default share, to offer the new one to. */
    fun stillDefault(view: SettledEvents, member: Member): List<Event> = view.band.events.filter { event -> canApply(view, event, member) && isDefault(member, event.participant(member.id)!!) }
}
