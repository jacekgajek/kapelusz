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
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate

/** Where an event link leads ([linkedEvent]): the user's band that has the event, found on the device or after a sync. */
class EventLinkOpenerTest :
    ExpectSpec({
        isolationMode = IsolationMode.InstancePerRoot
        coroutineTestScope = true
        var nextId = 0

        fun ids() = { "id${nextId++}" }

        suspend fun aliceWithGig(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, ids())
            repository.load("alice")
            repository.createBand("Night Owls", "PLN", "Alice")
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13)))
            return repository
        }

        expect("found on the device without asking the server") {
            val repository = aliceWithGig(coroutineTestScope)
            var synced = 0
            val found = linkedEvent("gig", "alice", repository) {
                synced++
                true
            }
            found shouldBe LinkedEvent.Found(repository.currentBandId!!)
            synced shouldBe 0
        }

        expect("found after a sync brings the band or else no access or unreachable") {
            val repository = aliceWithGig(coroutineTestScope)
            val other = Band.create("b2", "Other", "PLN", "m-zed", "Zed", "zed")
            val found = linkedEvent("later", "alice", repository) {
                // The sync brings a band where alice may open the event.
                val joined = listOf(AddMember("m-alice", "Alice"), LinkUser("m-alice", "alice", Role.MEMBER))
                val ops = joined + CreateEvent("later", "Later", LocalDate(2026, 10, 1))
                val band = ops.fold(other) { band, op -> (Engine.apply(band, Actor.System, op, "srv", 0) as ApplyResult.Applied).band }
                repository.adoptFromServer(band, "v1")
                true
            }
            found shouldBe LinkedEvent.Found("b2")
            linkedEvent("nowhere", "alice", repository) { true } shouldBe LinkedEvent.NoAccess
            linkedEvent("nowhere", "alice", repository) { false } shouldBe LinkedEvent.Unreachable
            linkedEvent("nowhere", "alice", repository) { error("offline") } shouldBe LinkedEvent.Unreachable
            withClue("signed out") { linkedEvent("gig", null, repository) { true } shouldBe LinkedEvent.Unreachable }
        }
    })
