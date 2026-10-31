package com.jacekgajek.kapelusz.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.data.Account
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.Attachments
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.Changes
import com.jacekgajek.kapelusz.app.data.Membership
import com.jacekgajek.kapelusz.app.data.PlacesLocator
import com.jacekgajek.kapelusz.app.data.SettingsRepository
import com.jacekgajek.kapelusz.app.intents.IntentInbox
import com.jacekgajek.kapelusz.app.sync.CalendarApi
import com.jacekgajek.kapelusz.app.sync.HistoryApi
import com.jacekgajek.kapelusz.app.sync.InvitesApi
import com.jacekgajek.kapelusz.app.sync.PlacesApi
import com.jacekgajek.kapelusz.app.sync.RawStateApi
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.rules.isArtist
import com.jacekgajek.kapelusz.view.BandView

/**
 * The open band as a screen sees it, and the app's services it may use: built once per band state ([BandState.Ready])
 * in [rememberBandSession], so every screen and section asks the same object instead of being handed a dozen things.
 * What a screen may do is [can], asked once per state rather than rebuilt in every composable.
 */
@Stable
class BandSession(val state: BandState.Ready, private val graph: AppGraph) {
    val view: BandView get() = state.view
    val band: Band get() = state.band
    val userId: String get() = state.userId
    val me: Member? get() = state.me
    val currency: String get() = band.currency

    /** What the user may do in this band state. */
    val can: Capabilities = Capabilities(band, userId)

    /** Sees only their own events, costs and payouts (ITERATION_4 §1.1). */
    val artist: Boolean = band.isArtist(userId)

    val changes: Changes get() = graph.changes
    val bands: BandRepository get() = graph.bands
    val settings: SettingsRepository get() = graph.settings
    val intents: IntentInbox get() = graph.intents
    val attachments: Attachments get() = graph.attachments
    val account: Account get() = graph.account
    val membership: Membership get() = graph.membership

    // What a screen may ask the server (sync/ScreenApis.kt): the parts, never the whole client.
    val placesApi: PlacesApi get() = graph.api
    val invitesApi: InvitesApi get() = graph.api
    val historyApi: HistoryApi get() = graph.api
    val calendarApi: CalendarApi get() = graph.api
    val rawStateApi: RawStateApi get() = graph.api

    /** Where event locations are, for the map (ITERATION_7 §8). */
    val locations: PlacesLocator get() = graph.locations

    /** A round of sync now, e.g. before reading the server's history, so it has what this device changed. */
    suspend fun syncNow() {
        graph.sync.syncNow()
    }
}

@Composable
fun rememberBandSession(state: BandState.Ready, graph: AppGraph): BandSession = remember(state, graph) { BandSession(state, graph) }
