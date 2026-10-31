package com.jacekgajek.kapelusz.app.devices

import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.bands.BandIndex
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import com.jacekgajek.kapelusz.app.sync.SyncEvent
import com.jacekgajek.kapelusz.app.sync.SyncState
import com.jacekgajek.kapelusz.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** [DeviceRegistration] registers on its own, whenever what the server keeps the device for changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRegistrationTest {
    private class Channel(var token: String? = "tok") : MessageChannel {
        override val installationId = "inst"
        var deleted = 0

        override suspend fun token() = token

        override suspend fun deleteToken() {
            deleted++
        }
    }

    private class Devices : DevicesApi {
        val registered = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var answer: () -> Boolean = { true }

        override suspend fun registerDevice(installationId: String, token: String): Boolean {
            registered += token
            return answer()
        }

        override suspend fun removeDevice(installationId: String) {
            removed += installationId
        }
    }

    /** The collectors live in a scope of their own (the test scope would wait for them), cancelled at the end. */
    private class Fixture(test: TestScope, val channel: Channel? = Channel()) {
        private val job = Job()
        val session = MutableStateFlow<Session?>(null)
        val index = MutableStateFlow<BandIndex?>(null)
        val events = MutableSharedFlow<SyncEvent>()
        val syncState = MutableStateFlow<SyncState>(SyncState.Stopped)
        val devices = Devices()
        val messaged = mutableListOf<Boolean>()
        var now = 1_000_000L
        val registration = DeviceRegistration(
            channel,
            session,
            index,
            devices,
            events,
            syncState,
            messaged = { messaged += it },
            scope = CoroutineScope(test.coroutineContext + job),
            clock = { now },
        )

        fun close() = job.cancel()
    }

    private fun signedIn(userId: String = "u1", apiToken: String? = "api"): Session {
        val farOff = 1_000_000_000L
        return Session(userId, idToken = "id", expiresAt = 0, apiToken = apiToken, apiExpiresAt = farOff)
    }

    private fun bands(vararg ids: String): BandIndex = BandIndex("u1", bands = ids.map { BandSummary(it, "Band $it", "Me", Role.MEMBER) })

    @Test
    fun registersOnceSignedInWithTheBandsLoadedAndAgainWhenBeingInABandChanges() = runTest {
        val f = Fixture(this)
        f.session.value = signedIn()
        advanceUntilIdle()
        assertEquals(emptyList(), f.devices.registered, "the bands are not loaded yet")
        f.index.value = bands()
        advanceUntilIdle()
        assertEquals(listOf("tok"), f.devices.registered, "signed in, no band: still registered (the server says no messages)")
        assertEquals(listOf(true), f.messaged)
        f.index.value = bands("b1")
        advanceUntilIdle()
        assertEquals(2, f.devices.registered.size, "a first band")
        f.index.value = bands("b1", "b2")
        advanceUntilIdle()
        assertEquals(2, f.devices.registered.size, "a second band changes nothing for the server")
        f.index.value = bands()
        advanceUntilIdle()
        assertEquals(3, f.devices.registered.size, "the last band gone")
        f.close()
    }

    @Test
    fun signingInAgainRegistersAgainEvenAsTheSameUserWithTheSameBands() = runTest {
        val f = Fixture(this)
        f.session.value = signedIn()
        f.index.value = bands("b1")
        advanceUntilIdle()
        assertEquals(1, f.devices.registered.size)
        f.session.value = null
        advanceUntilIdle()
        f.session.value = signedIn()
        advanceUntilIdle()
        assertEquals(2, f.devices.registered.size, "signing out removed the device")
        f.session.value = null
        f.session.value = signedIn("u2")
        advanceUntilIdle()
        assertEquals(3, f.devices.registered.size, "another user")
        f.close()
    }

    @Test
    fun aNewTokenAndABandTheServerNowHasRegisterAgain() = runTest {
        val f = Fixture(this)
        f.session.value = signedIn()
        f.index.value = bands("b1")
        advanceUntilIdle()
        assertEquals(1, f.devices.registered.size)
        f.registration.tokenChanged()
        advanceUntilIdle()
        assertEquals(2, f.devices.registered.size)
        f.events.emit(SyncEvent.BandCreated("b2"))
        advanceUntilIdle()
        assertEquals(3, f.devices.registered.size)
        f.close()
    }

    @Test
    fun comingToTheScreenRegistersAgainAfterAFailureOrADay() = runTest {
        val f = Fixture(this)
        f.devices.answer = { error("offline") }
        f.session.value = signedIn()
        f.index.value = bands("b1")
        advanceUntilIdle()
        assertEquals(listOf(false), f.messaged, "a failure leaves the app looking for news itself")
        f.devices.answer = { true }
        f.syncState.value = SyncState.Running()
        advanceUntilIdle()
        assertEquals(2, f.devices.registered.size, "tried again on screen")
        assertEquals(listOf(false, true), f.messaged)
        f.syncState.value = SyncState.Idle(0)
        advanceUntilIdle()
        f.syncState.value = SyncState.Stopped
        advanceUntilIdle()
        f.syncState.value = SyncState.Running()
        advanceUntilIdle()
        assertEquals(2, f.devices.registered.size, "not again the same day once it worked")
        f.now += 25 * 60 * 60_000L
        f.syncState.value = SyncState.Stopped
        advanceUntilIdle()
        f.syncState.value = SyncState.Running()
        advanceUntilIdle()
        assertEquals(3, f.devices.registered.size, "a day later: seen again, so the server keeps the device")
        f.close()
    }

    @Test
    fun nothingWithoutAChannelOrAToken() = runTest {
        val web = Fixture(this, channel = null)
        web.session.value = signedIn()
        web.index.value = bands("b1")
        advanceUntilIdle()
        assertEquals(emptyList(), web.devices.registered)
        val f = Fixture(this, Channel(token = null))
        f.session.value = signedIn()
        f.index.value = bands("b1")
        advanceUntilIdle()
        assertEquals(emptyList(), f.devices.registered)
        assertEquals(listOf(false), f.messaged)
        web.close()
        f.close()
    }

    @Test
    fun unregisteringTellsTheServerOnlyWithAUsableSessionAndDeletesTheToken() = runTest {
        val f = Fixture(this)
        f.session.value = signedIn()
        f.index.value = bands("b1")
        advanceUntilIdle()
        f.registration.unregister(tellServer = true)
        assertEquals(listOf("inst"), f.devices.removed)
        assertEquals(1, f.channel!!.deleted)
        assertEquals(listOf(true, false), f.messaged)
        f.session.value = signedIn(apiToken = null)
        f.registration.unregister(tellServer = true)
        assertEquals(1, f.devices.removed.size, "a session that would need renewing: the deleted token is enough")
        assertEquals(2, f.channel.deleted)
        f.registration.unregister(tellServer = false)
        assertEquals(1, f.devices.removed.size)
        f.close()
    }
}
