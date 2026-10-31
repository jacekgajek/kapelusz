package com.jacekgajek.kapelusz.engine

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Member

// Replacing one entity inside the band, or failing when it is not there; see [Reducer].

internal inline fun Band.updateEvent(eventId: Id, transform: (Event) -> Event): Band {
    val event = event(eventId).orNotFound { "Event $eventId" }
    val updated = transform(event)
    return copy(events = events.map { if (it.id == eventId) updated else it })
}

internal inline fun Band.updateMember(memberId: Id, transform: (Member) -> Member): Band {
    val member = member(memberId).orNotFound { "Member $memberId" }
    val updated = transform(member)
    return copy(members = members.map { if (it.id == memberId) updated else it })
}

internal inline fun Band.updateCost(costId: Id, transform: (Cost) -> Cost): Band {
    val cost = cost(costId).orNotFound { "Cost $costId" }
    val updated = transform(cost)
    return copy(costs = costs.map { if (it.id == costId) updated else it })
}
