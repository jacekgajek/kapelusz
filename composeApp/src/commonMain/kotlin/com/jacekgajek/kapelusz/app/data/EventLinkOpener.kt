package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.text.EventLinks

/** Where an event link leads (ITERATION_5 §10). */
internal sealed interface LinkedEvent {
    data class Found(val bandId: Id) : LinkedEvent

    /** None of the user's bands has it for them, as the server has them now. */
    data object NoAccess : LinkedEvent

    /** Not on this device, and the server could not be asked (offline, or signing in again needed). */
    data object Unreachable : LinkedEvent
}

/**
 * The band of an event link's event: one of the user's bands that has it and where they may open it. Looked for on the
 * device first; if not there (a gig just created, a band not on this device yet), every band of the user's is brought
 * up to date from the server and looked in again.
 */
internal suspend fun AppGraph.linkedEvent(eventId: Id): LinkedEvent {
    val userId = sessions.session.value?.userId ?: return LinkedEvent.Unreachable
    fun find() = bands.allBands().firstOrNull { band -> band.event(eventId)?.let { EventLinks.canOpen(band, userId, it) } == true }?.id
    find()?.let { return LinkedEvent.Found(it) }
    val synced = runSuspendCatching { sync.syncAll() }.getOrDefault(false)
    find()?.let { return LinkedEvent.Found(it) }
    return if (synced) LinkedEvent.NoAccess else LinkedEvent.Unreachable
}
