package com.jacekgajek.kapelusz.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.jacekgajek.kapelusz.app.attachments.Attachments
import com.jacekgajek.kapelusz.app.auth.Account
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.bands.Changes
import com.jacekgajek.kapelusz.app.bands.Membership
import com.jacekgajek.kapelusz.app.bands.PlacesLocator
import com.jacekgajek.kapelusz.app.intents.IntentInbox
import com.jacekgajek.kapelusz.app.runtime.AppRuntime
import com.jacekgajek.kapelusz.app.settings.SettingsRepository
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
class BandSession(val state: BandState.Ready, private val runtime: AppRuntime) {
    val view: BandView get() = state.view
    val band: Band get() = state.band
    val userId: String get() = state.userId
    val me: Member? get() = state.me
    val currency: String get() = band.currency

    /** What the user may do in this band state. */
    val can: Capabilities = Capabilities(band, userId)

    /** Sees only their own events, costs and payouts (I4 §1.1). */
    val artist: Boolean = band.isArtist(userId)

    val changes: Changes get() = runtime.changes
    val bands: BandRepository get() = runtime.bands
    val settings: SettingsRepository get() = runtime.settings
    val intents: IntentInbox get() = runtime.intents
    val attachments: Attachments get() = runtime.attachments
    val account: Account get() = runtime.account
    val membership: Membership get() = runtime.membership

    // What a screen may ask the server (sync/ScreenApis.kt): the parts, never the whole client.
    val placesApi: PlacesApi get() = runtime.api
    val invitesApi: InvitesApi get() = runtime.api
    val historyApi: HistoryApi get() = runtime.api
    val calendarApi: CalendarApi get() = runtime.api
    val rawStateApi: RawStateApi get() = runtime.api

    /** Where event locations are, for the map (I7 §8). */
    val locations: PlacesLocator get() = runtime.locations

    /** A round of sync now, e.g. before reading the server's history, so it has what this device changed. */
    suspend fun syncNow() {
        runtime.sync.syncNow()
    }
}

@Composable
fun rememberBandSession(state: BandState.Ready, runtime: AppRuntime): BandSession = remember(state, runtime) { BandSession(state, runtime) }
