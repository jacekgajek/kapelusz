package com.jacekgajek.kapelusz.app.bands

import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.rules.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** Where an event link leads ([linkedEvent]): the user's band that has the event, found on the device or after a sync. */
class EventLinkOpenerTest {
    private var nextId = 0
    private fun ids() = { "id${nextId++}" }

    private suspend fun TestScope.aliceWithGig(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, ids())
        repository.load("alice")
        repository.createBand("Night Owls", "PLN", "Alice")
        repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13)))
        return repository
    }

    @Test
    fun foundOnTheDeviceWithoutAskingTheServer() = runTest {
        val repository = aliceWithGig()
        var synced = 0
        val found = linkedEvent("gig", "alice", repository) {
            synced++
            true
        }
        assertEquals(LinkedEvent.Found(repository.currentBandId!!), found)
        assertEquals(0, synced)
    }

    @Test
    fun foundAfterASyncBringsTheBandOrElseNoAccessOrUnreachable() = runTest {
        val repository = aliceWithGig()
        val other = Band.create("b2", "Other", "PLN", "m-zed", "Zed", "zed")
        val found = linkedEvent("later", "alice", repository) {
            // The sync brings a band where alice may open the event.
            val joined = listOf(AddMember("m-alice", "Alice"), LinkUser("m-alice", "alice", Role.MEMBER))
            val ops = joined + CreateEvent("later", "Later", LocalDate(2026, 10, 1))
            val band = ops.fold(other) { band, op -> (Engine.apply(band, Actor.System, op, "srv", 0) as ApplyResult.Applied).band }
            repository.adoptFromServer(band, "v1")
            true
        }
        assertEquals(LinkedEvent.Found("b2"), found)
        assertEquals(LinkedEvent.NoAccess, linkedEvent("nowhere", "alice", repository) { true })
        assertEquals(LinkedEvent.Unreachable, linkedEvent("nowhere", "alice", repository) { false })
        assertEquals(LinkedEvent.Unreachable, linkedEvent("nowhere", "alice", repository) { error("offline") })
        assertEquals(LinkedEvent.Unreachable, linkedEvent("gig", null, repository) { true }, "signed out")
    }
}
