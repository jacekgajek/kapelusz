package com.jacekgajek.kapelusz.app.auth

import com.jacekgajek.kapelusz.app.attachments.AttachmentUploads
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.devices.DeviceRegistration
import com.jacekgajek.kapelusz.app.intents.IntentInbox
import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.app.sync.SyncEngine
import com.jacekgajek.kapelusz.app.util.detached
import com.jacekgajek.kapelusz.app.util.runSuspendCatching
import com.jacekgajek.kapelusz.model.Member
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Signing in and out of this device, and what goes with it: the user's bands, sync, sync messages. */
class Account(
    private val scope: CoroutineScope,
    private val sessions: SessionRepository,
    private val api: ApiClient,
    private val bands: BandRepository,
    private val uploads: AttachmentUploads,
    private val sync: SyncEngine,
    private val devices: DeviceRegistration,
    private val intents: IntentInbox,
) {
    /** The signed-in user; null while signed out. */
    val session: StateFlow<Session?> get() = sessions.session

    private val _signedOutEverywhere = MutableStateFlow(false)

    /** The user was signed out because of "sign out on all devices" (I3 R6); the sign-in screen says so. */
    val signedOutEverywhere: StateFlow<Boolean> = _signedOutEverywhere.asStateFlow()

    /**
     * [detached] from the caller: the sign-in screen leaves the composition as soon as the session appears, which
     * would cancel the loading half-way and leave a blank screen. The bands are loaded before the session is
     * published, so the UI switches straight to the loaded band.
     */
    suspend fun onSignedIn(idToken: String) = scope.detached {
        val userId = Session.fromIdToken(idToken).userId
        bands.load(userId)
        uploads.load(userId)
        sessions.signedIn(idToken)
        _signedOutEverywhere.value = false
        sync.start()
        sync.resumeAfterSignIn()
    }

    /**
     * Signs out in the app's own scope (I5 A5c): the screen that asked goes away as soon as the session does, and
     * its scope with it, which must not stop the rest of signing out.
     */
    fun signOutInBackground() {
        scope.launch { signOut() }
    }

    /**
     * [tellServer]: remove this device from the server's list. Not after "sign out on all devices" (the server cleared
     * the list, and would refuse the request, signing out again), nor with a session that would need renewing (that
     * could show Google's sign-in): the deleted token is enough, as the server forgets it at the next message.
     */
    suspend fun signOut(tellServer: Boolean = true) {
        sync.stop()
        devices.unregister(tellServer)
        sessions.signOut()
        intents.signedOut()
    }

    /**
     * "Sign out on all devices" (I3 R6): the server ends every session of the user, this device's included,
     * then this device signs out as [signOut] does. Throws (and stays signed in) when the server could not be told.
     */
    suspend fun signOutEverywhere() {
        api.revokeSessions()
        signOut(tellServer = false)
    }

    /**
     * The server refuses this device's tokens after "sign out on all devices" (here or elsewhere): signs out as
     * [signOut] does, which also keeps Google from signing in again silently. Several requests may be refused at once.
     */
    internal suspend fun signOutRevoked() {
        if (sessions.session.value == null) return
        _signedOutEverywhere.value = true
        signOut(tellServer = false)
    }

    private var pictureSent = false

    /**
     * Once per app run, while the user's member has no photo: the Google token kept from signing in carries it, so
     * members signed in before photos existed get theirs without signing in again (I2 §7.4).
     */
    fun sendPictureIfMissing(me: Member?) {
        if (pictureSent || me == null || me.pictureUrl != null) return
        val googleToken = sessions.session.value?.idToken ?: return
        pictureSent = true
        scope.launch { runSuspendCatching { api.sendPicture(googleToken) }.onSuccess { sync.requestSync() } }
    }
}
