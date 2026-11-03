package com.jacekgajek.kapelusz.app.bands

import com.jacekgajek.kapelusz.api.BandSummary
import com.jacekgajek.kapelusz.api.PushResponse
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.storage.KeyValueStore
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.RenameBand
import com.jacekgajek.kapelusz.ops.SetRefund
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class BandRepositoryTest :
    ExpectSpec({
        isolationMode = IsolationMode.InstancePerRoot
        coroutineTestScope = true
        var nextId = 0

        fun ids() = { "id${nextId++}" }

        fun ready(repository: BandRepository) = repository.state.value.shouldBeInstanceOf<BandState.Ready>()

        expect("starts without band") {
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, ids())
            repository.load("alice")
            repository.state.value shouldBe BandState.NoBand
        }

        expect("a created band is queued for creation and persisted with pending ops") {
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, ids(), clock = { 1000 })
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            testScheduler.advanceUntilIdle()

            val bandId = ready(repository).band.id
            repository.bandsToPush() shouldBe listOf(bandId)
            val reloaded = BandRepository(store, TestScope(testScheduler), ids())
            reloaded.load("alice")
            val state = ready(reloaded)
            state.band.members.map { it.name } shouldBe listOf("Alice", "Bob")
            state.me.shouldNotBeNull().name shouldBe "Alice"
            reloaded.local(bandId)!!.needsCreate shouldBe true
            reloaded.local(bandId)!!.pending shouldHaveSize 2
            reloaded.index.value!!.bands.map { it.name } shouldBe listOf("The Band")
        }

        // A change made while the band is being written is written too, not taken as saved with the old copy.
        expect("a change made during a write is written too") {
            val inner = InMemoryStore()
            val gate = CompletableDeferred<Unit>()
            var holdNextBandWrite = false
            val store = object : KeyValueStore by inner {
                override suspend fun write(key: String, value: String) {
                    if (holdNextBandWrite && key.startsWith("band-alice-")) {
                        holdNextBandWrite = false
                        gate.await()
                    }
                    inner.write(key, value)
                }
            }
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            testScheduler.advanceUntilIdle()
            val bandId = ready(repository).band.id

            holdNextBandWrite = true
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            testScheduler.runCurrent()
            // The write of the band with Bob is under way; Carol comes now.
            repository.dispatch(AddMember("carol", "Carol")).shouldBeNull()
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            val reloaded = BandRepository(inner, TestScope(testScheduler), ids())
            reloaded.load("alice")
            ready(reloaded).band.id shouldBe bandId
            ready(reloaded).band.members.map { it.name } shouldBe listOf("Alice", "Bob", "Carol")
        }

        expect("rejected operations are not queued") {
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            val before = ready(repository).band
            repository.dispatch(SetRefund("missing", "cost", Money(1)))?.code shouldBe RejectionCode.NOT_FOUND
            ready(repository).band shouldBe before
            repository.local(before.id)!!.pending.shouldBeEmpty()
        }

        expect("server state is the new base and unsent changes are reapplied on top") {
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            val bandId = ready(repository).band.id
            val created = repository.local(bandId)!!.base
            repository.onCreated(bandId, created, "v1")
            repository.dispatch(RenameBand("Sent"))
            val sent = repository.local(bandId)!!.pending
            repository.dispatch(AddMember("bob", "Bob")) // made while the push is in flight

            val serverBand = created.copy(
                name = "Sent",
                members = created.members + created.members.first().copy(id = "carol", name = "Carol", userId = null, role = Role.MEMBER),
            )
            val response = PushResponse("v2", serverBand, accepted = sent.map { it.opId }, rejected = emptyMap(), duplicates = emptyList())
            repository.onPushed(bandId, response)

            val local = repository.local(bandId)!!
            local.version shouldBe "v2"
            local.pending shouldHaveSize 1
            ready(repository).band.members.map { it.name } shouldBe listOf("Alice", "Carol", "Bob")
        }

        expect("switches between bands") {
            val repository = BandRepository(InMemoryStore(), coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("First", "PLN", "Alice")
            val first = ready(repository).band.id
            repository.createBand("Second", "EUR", "Alice")
            ready(repository).band.name shouldBe "Second"
            repository.open(first)
            ready(repository).band.name shouldBe "First"
            repository.currentBandId shouldBe first
        }

        expect("unreadable data is backed up before starting over") {
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, ids(), clock = { 42 })
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            val bandId = ready(repository).band.id
            testScheduler.advanceUntilIdle()
            store.values[BandStore.bandKey("alice", bandId)] = "{ not valid"

            val reloaded = BandRepository(store, TestScope(testScheduler), ids(), clock = { 42 })
            reloaded.load("alice")
            val state = reloaded.state.value.shouldBeInstanceOf<BandState.Unreadable>()
            store.values[state.backupKey] shouldBe "{ not valid"

            reloaded.startOver()
            reloaded.state.value shouldBe BandState.NoBand
            store.values[BandStore.bandKey("alice", bandId)].shouldBeNull()
            withClue("the backup survives starting over") { store.values[state.backupKey] shouldBe "{ not valid" }
        }

        expect("bands that are not open still sync after a restart") {
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("Made offline", "PLN", "Alice")
            val offline = repository.currentBandId!!
            repository.createBand("Open one", "PLN", "Alice")
            testScheduler.advanceUntilIdle()

            val restarted = BandRepository(store, TestScope(testScheduler), ids())
            restarted.load("alice")
            ready(restarted).band.name shouldBe "Open one"
            withClue("the band made offline is still queued for upload") { restarted.bandsToPush() shouldContain offline }
            restarted.onBandList(emptyList()) // the server does not know it yet
            withClue("and it stays in the band list") { restarted.index.value!!.bands.any { it.id == offline } shouldBe true }
        }

        expect("another account on the same device does not see the bands") {
            val store = InMemoryStore()
            val alice = BandRepository(store, coroutineTestScope, ids())
            alice.load("alice")
            alice.createBand("Alice's band", "PLN", "Alice")
            alice.dispatch(AddMember("bob", "Bob"))
            val bandId = alice.currentBandId!!
            testScheduler.advanceUntilIdle()

            val bob = BandRepository(store, TestScope(testScheduler), ids())
            bob.load("bob")
            bob.state.value shouldBe BandState.NoBand
            bob.bandsToPush().shouldBeEmpty()
            bob.hasStored(bandId) shouldBe false

            val aliceAgain = BandRepository(store, TestScope(testScheduler), ids())
            aliceAgain.load("alice")
            withClue("Alice's unsent change is still there") { aliceAgain.local(bandId)!!.pending shouldHaveSize 1 }
        }

        expect("flush writes pending changes immediately") {
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob"))
            repository.flush()
            val raw = store.values.getValue(BandStore.bandKey("alice", repository.currentBandId!!))
            val saved = KapeluszJson.decodeFromString(LocalBand.serializer(), raw)
            saved.nextSeq shouldBe 2L
        }

        expect("forgetting a deleted band opens another one") {
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("Kept", "PLN", "Alice")
            val kept = repository.currentBandId!!
            repository.createBand("Deleted", "PLN", "Alice")
            val deleted = repository.currentBandId!!
            repository.flush()

            repository.forgetDeleted(deleted)
            repository.currentBandId shouldBe kept
            repository.index.value!!.bands.map { it.name } shouldBe listOf("Kept")
            store.read(BandStore.bandKey("alice", deleted)).shouldBeNull()
            withClue("no backup is kept for a band deleted on purpose") { store.values.keys.none { it.contains(deleted) } shouldBe true }
        }

        expect("a band stored by an older app is downloaded in full once") {
            // An older app dropped fields it did not know but kept the server's version, so "not modified" would never
            // bring them back.
            val store = InMemoryStore()
            val band = Band.create("b1", "Night Owls", "PLN", "m1", "Alice", "alice")
            val old = LocalBand(base = band, version = "\"etag-1\"", clientId = "c", userId = "alice")
            store.write(BandStore.bandKey("alice", "b1"), KapeluszJson.encodeToString(LocalBand.serializer(), old))
            val index = BandIndex("alice", "b1", listOf(BandSummary("b1", "Night Owls", "Alice", Role.OWNER)))
            store.write(BandStore.indexKey("alice"), KapeluszJson.encodeToString(BandIndex.serializer(), index))
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.local("b1")!!.version.shouldBeNull()
            repository.flush()
            val saved = KapeluszJson.decodeFromString(LocalBand.serializer(), store.read(BandStore.bandKey("alice", "b1"))!!)
            saved.schema shouldBe BandRepository.CURRENT_SCHEMA
        }

        // An unsent change of a kind this version doesn't know (a MoveMember) is dropped, the rest kept.
        expect("an unsent change of an unknown kind is dropped") {
            val store = InMemoryStore()
            val band = Band.create("b1", "Night Owls", "PLN", "m1", "Alice", "alice")
            val stored = LocalBand(
                base = band,
                version = "\"etag-1\"",
                clientId = "c",
                userId = "alice",
                schema = BandRepository.CURRENT_SCHEMA,
            )
            val pending =
                """[{"opId":"o1","clientId":"c","seq":1,"timestamp":1,"op":{"type":"MoveMember","memberId":"m1","toIndex":0}},""" +
                    """{"opId":"o2","clientId":"c","seq":2,"timestamp":2,"op":{"type":"RenameBand","name":"Owls"}}]"""
            val raw = KapeluszJson.encodeToString(LocalBand.serializer(), stored).replaceFirst("{", "{\"pending\":$pending,")
            store.write(BandStore.bandKey("alice", "b1"), raw)
            val index = BandIndex("alice", "b1", listOf(BandSummary("b1", "Night Owls", "Alice", Role.OWNER)))
            store.write(BandStore.indexKey("alice"), KapeluszJson.encodeToString(BandIndex.serializer(), index))
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.local("b1")!!.pending.map { it.opId } shouldBe listOf("o2")
            ready(repository).band.name shouldBe "Owls"
        }

        expect("a band already on the device is not overwritten by a background download") {
            val store = InMemoryStore()
            val repository = BandRepository(store, coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            testScheduler.advanceUntilIdle()
            val bandId = ready(repository).band.id
            // The index is lost (unreadable): the band's file is still there, with its unsent change.
            store.values["band-index-alice"] = "{ not json"
            val reloaded = BandRepository(store, TestScope(testScheduler), ids())
            reloaded.load("alice")
            withClue("the unreadable index is kept") { store.values.keys.any { it.startsWith("band-index-alice-backup-") } shouldBe true }
            reloaded.storeFromServer(com.jacekgajek.kapelusz.model.Band.create(bandId, "The Band", "PLN", "alice", "Alice", "alice"), "v1")
            withClue("the unsent change survives the background download") { reloaded.local(bandId)!!.pending shouldHaveSize 1 }
        }

        expect("a failed write is kept and tried again") {
            val failing = object : KeyValueStore {
                val values = mutableMapOf<String, String>()
                var full = false
                override suspend fun read(key: String) = values[key]
                override suspend fun write(key: String, value: String) {
                    if (full) error("No space left on device")
                    values[key] = value
                }
                override suspend fun remove(key: String) {
                    values.remove(key)
                }
            }
            val repository = BandRepository(failing, coroutineTestScope, ids())
            repository.load("alice")
            repository.createBand("The Band", "PLN", "Alice")
            testScheduler.advanceUntilIdle()
            val bandId = ready(repository).band.id
            failing.full = true
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            testScheduler.runCurrent() // the write fails, without crashing
            failing.full = false
            testScheduler.advanceTimeBy(BandStore.FLUSH_RETRY_MS + 1)
            testScheduler.runCurrent()
            val stored = failing.values.getValue("band-alice-$bandId")
            withClue("written once the device has space again") { stored shouldContain "Bob" }
        }
    })
