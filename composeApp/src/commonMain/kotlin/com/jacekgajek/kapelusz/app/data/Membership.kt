package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** The user's bands as a whole: joining one, leaving or deleting the open one. */
class Membership(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val bands: BandRepository,
    private val sync: SyncEngine,
    private val messages: DeviceMessages,
) {
    /** What an invite code leads to, before accepting it. Network errors and refusals surface as exceptions. */
    suspend fun previewInvite(code: String) = api.previewInvite(code)

    /** Accepts an invite and opens the band. Network errors and refusals surface as exceptions. */
    suspend fun joinBand(code: String) {
        val joined = api.acceptInvite(code)
        bands.adoptFromServer(joined.band, joined.version)
        sync.requestSync()
        // Possibly a first band: the server keeps the device only while the user is in one.
        messages.register()
    }

    /**
     * Asks to delete the open band for everyone (owner only, ITERATION_7 §9): the server deletes it in a week, unless
     * a leader keeps it; the next sync brings the notice. A band never uploaded, which nobody else has, goes at once.
     * Network errors and refusals surface as exceptions.
     */
    suspend fun deleteBand() {
        val bandId = bands.currentBandId ?: return
        if (bands.local(bandId)?.needsCreate == true) {
            bands.forgetDeleted(bandId)
            return
        }
        api.deleteBand(bandId)
        sync.requestSync()
    }

    /**
     * Leaves the open band (not as its owner), see [SyncEngine.leave]. Network errors and refusals surface as
     * exceptions; on success the band is gone from this device. In the app scope: the band's screens go away as soon
     * as it is forgotten, which must not cancel it half-way.
     */
    suspend fun leaveBand() {
        val bandId = bands.currentBandId ?: return
        scope.async { sync.leave(bandId) }.await()
        // Maybe the last band: the server then forgot this device, and the answer says no messages come.
        messages.register()
    }

    /**
     * Discards the unsent changes of a band the server refuses them for (ITERATION_3 R9); in the app scope, since the
     * banner asking for it leaves as soon as the band is no longer stuck.
     */
    fun discardStuck(bandId: String) {
        scope.launch { sync.discardStuck(bandId) }
    }
}
