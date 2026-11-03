package com.jacekgajek.kapelusz.app.devices

import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.app.auth.Session
import com.jacekgajek.kapelusz.app.bands.BandIndex
import com.jacekgajek.kapelusz.app.platform.MessageChannel
import com.jacekgajek.kapelusz.app.sync.SyncEvent
import com.jacekgajek.kapelusz.app.sync.SyncState
import com.jacekgajek.kapelusz.model.Role
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle

private class DeviceChannel(var token: String? = "tok") : MessageChannel {
    override val installationId = "inst"
    var deleted = 0

    override suspend fun token() = token

    override suspend fun deleteToken() {
        deleted++
    }
}

private class FakeDevices : DevicesApi {
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
private class RegistrationFixture(test: CoroutineScope, val channel: DeviceChannel? = DeviceChannel()) {
    private val job = Job()
    val session = MutableStateFlow<Session?>(null)
    val index = MutableStateFlow<BandIndex?>(null)
    val events = MutableSharedFlow<SyncEvent>()
    val syncState = MutableStateFlow<SyncState>(SyncState.Stopped)
    val devices = FakeDevices()
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

/** [DeviceRegistration] registers on its own, whenever what the server keeps the device for changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRegistrationTest :
    ExpectSpec({
        coroutineTestScope = true

        fun signedIn(userId: String = "u1", apiToken: String? = "api"): Session {
            val farOff = 1_000_000_000L
            return Session(userId, idToken = "id", expiresAt = 0, apiToken = apiToken, apiExpiresAt = farOff)
        }

        fun bands(vararg ids: String): BandIndex = BandIndex("u1", bands = ids.map { BandSummary(it, "Band $it", "Me", Role.MEMBER) })

        expect("registers once signed in with the bands loaded and again when being in a band changes") {
            val f = RegistrationFixture(coroutineTestScope)
            f.session.value = signedIn()
            coroutineTestScope.advanceUntilIdle()
            withClue("the bands are not loaded yet") { f.devices.registered.shouldBeEmpty() }
            f.index.value = bands()
            coroutineTestScope.advanceUntilIdle()
            withClue("signed in, no band: still registered (the server says no messages)") { f.devices.registered shouldBe listOf("tok") }
            f.messaged shouldBe listOf(true)
            f.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            withClue("a first band") { f.devices.registered.size shouldBe 2 }
            f.index.value = bands("b1", "b2")
            coroutineTestScope.advanceUntilIdle()
            withClue("a second band changes nothing for the server") { f.devices.registered.size shouldBe 2 }
            f.index.value = bands()
            coroutineTestScope.advanceUntilIdle()
            withClue("the last band gone") { f.devices.registered.size shouldBe 3 }
            f.close()
        }

        expect("signing in again registers again even as the same user with the same bands") {
            val f = RegistrationFixture(coroutineTestScope)
            f.session.value = signedIn()
            f.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            f.devices.registered.size shouldBe 1
            f.session.value = null
            coroutineTestScope.advanceUntilIdle()
            f.session.value = signedIn()
            coroutineTestScope.advanceUntilIdle()
            withClue("signing out removed the device") { f.devices.registered.size shouldBe 2 }
            f.session.value = null
            f.session.value = signedIn("u2")
            coroutineTestScope.advanceUntilIdle()
            withClue("another user") { f.devices.registered.size shouldBe 3 }
            f.close()
        }

        expect("a new token and a band the server now has register again") {
            val f = RegistrationFixture(coroutineTestScope)
            f.session.value = signedIn()
            f.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            f.devices.registered.size shouldBe 1
            f.registration.tokenChanged()
            coroutineTestScope.advanceUntilIdle()
            f.devices.registered.size shouldBe 2
            f.events.emit(SyncEvent.BandCreated("b2"))
            coroutineTestScope.advanceUntilIdle()
            f.devices.registered.size shouldBe 3
            f.close()
        }

        expect("coming to the screen registers again after a failure or a day") {
            val f = RegistrationFixture(coroutineTestScope)
            f.devices.answer = { error("offline") }
            f.session.value = signedIn()
            f.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            withClue("a failure leaves the app looking for news itself") { f.messaged shouldBe listOf(false) }
            f.devices.answer = { true }
            f.syncState.value = SyncState.Running()
            coroutineTestScope.advanceUntilIdle()
            withClue("tried again on screen") { f.devices.registered.size shouldBe 2 }
            f.messaged shouldBe listOf(false, true)
            f.syncState.value = SyncState.Idle(0)
            coroutineTestScope.advanceUntilIdle()
            f.syncState.value = SyncState.Stopped
            coroutineTestScope.advanceUntilIdle()
            f.syncState.value = SyncState.Running()
            coroutineTestScope.advanceUntilIdle()
            withClue("not again the same day once it worked") { f.devices.registered.size shouldBe 2 }
            f.now += 25 * 60 * 60_000L
            f.syncState.value = SyncState.Stopped
            coroutineTestScope.advanceUntilIdle()
            f.syncState.value = SyncState.Running()
            coroutineTestScope.advanceUntilIdle()
            withClue("a day later: seen again, so the server keeps the device") { f.devices.registered.size shouldBe 3 }
            f.close()
        }

        expect("nothing without a channel or a token") {
            val web = RegistrationFixture(coroutineTestScope, channel = null)
            web.session.value = signedIn()
            web.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            web.devices.registered.shouldBeEmpty()
            val f = RegistrationFixture(coroutineTestScope, DeviceChannel(token = null))
            f.session.value = signedIn()
            f.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            f.devices.registered.shouldBeEmpty()
            f.messaged shouldBe listOf(false)
            web.close()
            f.close()
        }

        expect("unregistering tells the server only with a usable session and deletes the token") {
            val f = RegistrationFixture(coroutineTestScope)
            f.session.value = signedIn()
            f.index.value = bands("b1")
            coroutineTestScope.advanceUntilIdle()
            f.registration.unregister(tellServer = true)
            f.devices.removed shouldBe listOf("inst")
            f.channel!!.deleted shouldBe 1
            f.messaged shouldBe listOf(true, false)
            f.session.value = signedIn(apiToken = null)
            f.registration.unregister(tellServer = true)
            withClue("a session that would need renewing: the deleted token is enough") { f.devices.removed.size shouldBe 1 }
            f.channel.deleted shouldBe 2
            f.registration.unregister(tellServer = false)
            f.devices.removed.size shouldBe 1
            f.close()
        }
    })
