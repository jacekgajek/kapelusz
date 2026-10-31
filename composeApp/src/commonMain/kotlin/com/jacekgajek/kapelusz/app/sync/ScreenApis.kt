package com.jacekgajek.kapelusz.app.sync

import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.api.InvitesResponse
import com.jacekgajek.kapelusz.api.PlaceSuggestion
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.Role

// What a screen may ask the server, as small interfaces (REFACTORING.md 3.7): a screen names what it needs, and only
// [ApiClient] knows the whole API. Each call throws when it cannot (offline, refused, signing in again needed).

/** Google's place services for an event's Location (ITERATION_5 §9), through the server. */
interface PlacesApi {
    /** Suggestions for [query] in [language]; [session] groups a search with the details picked from it for Google's billing. */
    suspend fun places(bandId: Id, query: String, language: String, session: String): List<PlaceSuggestion>

    /** The picked place's name and full address. */
    suspend fun placeText(bandId: Id, placeId: String, language: String, session: String): String

    /** A Google Maps link read: the place and its address, or a pin's coordinates. */
    suspend fun resolveMapLink(bandId: Id, url: String, language: String): String
}

/** The band's invites (ITERATION_5 §12), for its roster. */
interface InvitesApi {
    suspend fun invites(bandId: Id): InvitesResponse

    suspend fun createInvite(bandId: Id, memberId: Id, role: Role): InviteInfo

    suspend fun revokeInvite(bandId: Id, code: String)
}

/** The band's history of changes (ITERATION_2 §7.2), by month, newest first. */
interface HistoryApi {
    /** [months] back from [month]; [before]: the page after the one that ended there. */
    suspend fun history(bandId: Id, month: String, before: String? = null, months: Int = 1): HistoryPage
}

/** The user's calendar subscription (ITERATION_7 §7). */
interface CalendarApi {
    suspend fun calendar(): CalendarSubscription

    suspend fun changeCalendar(change: CalendarSubscriptionChange): CalendarSubscription
}

/** The band as the server stores it, for the secret menu (ITERATION_2 §7.1). */
interface RawStateApi {
    suspend fun rawState(bandId: Id): String
}
