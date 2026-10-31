package com.jacekgajek.kapelusz.app.intents

import androidx.compose.runtime.MutableState
import androidx.navigation.NavHostController
import com.jacekgajek.kapelusz.app.data.BandRepository
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.runSuspendCatching
import com.jacekgajek.kapelusz.app.resetTo
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.ui.CostRoute
import com.jacekgajek.kapelusz.app.ui.EventRoute
import com.jacekgajek.kapelusz.app.ui.EventSection
import com.jacekgajek.kapelusz.app.ui.EventsRoute
import kotlinx.coroutines.flow.first

/**
 * The one object that both opens bands and sets up their screens (REFACTORING.md 3.6). Any other band shown than the
 * last one (also after having none: access lost, then one opened, created or joined) starts on its event list (V5-M1
 * C2b), as the navigation host keeps its back stack meanwhile and the old band's screens would come back; not the
 * app's first band, as a web address must survive. [shownBandId] is whose screens are up, saved over recreation.
 */
class BandNavigator(
    private val nav: NavHostController,
    private val bands: BandRepository,
    private val sync: SyncEngine,
    private val shownBandId: MutableState<String?>,
) {
    /**
     * Opens [bandId] (downloading it if it isn't on the device); false when that failed. [forScreens]: the caller sets
     * up its screens next, so showing it must not reset them to the event list.
     */
    suspend fun openBand(bandId: String, forScreens: Boolean): Boolean {
        val before = shownBandId.value
        // Before opening: showing the band may run [bandShown] before this returns.
        if (forScreens) shownBandId.value = bandId
        var opened = false
        try {
            if (bands.hasStored(bandId)) bands.open(bandId) else runSuspendCatching { sync.download(bandId) }
            // Not when the stored band could not be read, or the download failed: nothing is shown then.
            opened = bands.state.value is BandState.Ready && bands.currentBandId == bandId
        } finally {
            // Also when cut short (an opening cancelled): the screens are still the band's shown before.
            if (!opened && forScreens) shownBandId.value = before
        }
        return opened
    }

    /** The band shown changed ([readyBandId], or none): its screens start over unless they were set up for it here. */
    fun bandShown(readyBandId: String?, noBand: Boolean) {
        when {
            noBand -> shownBandId.value = shownBandId.value?.let { NO_BAND }

            readyBandId != null && readyBandId != shownBandId.value -> {
                if (shownBandId.value != null) nav.resetTo(EventsRoute)
                shownBandId.value = readyBandId
            }
        }
    }

    /** Back to the event list, nothing else on the stack (switching bands from inside one, joining one). */
    fun eventList() = nav.resetTo(EventsRoute)

    /**
     * The open band's event, from its event list (like after switching bands: Back must not lead into another band's
     * screens); with [carryId] for the screen. Waits for the screens to exist (a first band from a link: they compose
     * once the band opens).
     */
    suspend fun showEvent(eventId: String, section: EventSection? = null, carryId: String? = null) {
        fromEventList()
        nav.navigate(EventRoute(eventId, section, carryId))
    }

    /** The open band's event, with the form for a new cost of it on top (shared files go there, [carryId]). */
    suspend fun showNewCost(eventId: String, carryId: String?) {
        fromEventList()
        nav.navigate(EventRoute(eventId))
        nav.navigate(CostRoute(forEventId = eventId, carryId = carryId))
    }

    private suspend fun fromEventList() {
        nav.currentBackStackEntryFlow.first()
        nav.resetTo(EventsRoute)
    }

    private companion object {
        /** A band was shown, then none. */
        const val NO_BAND = "-"
    }
}
