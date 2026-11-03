package com.jacekgajek.kapelusz.app.bands

import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** The store's own rules ([BandStore]): what is written when, under which keys, and what a mark means. */
@OptIn(ExperimentalCoroutinesApi::class)
class BandStoreTest :
    ExpectSpec({
        coroutineTestScope = true
        val band = Band.create("b1", "Band", "PLN", "alice", "Alice", "user-alice")
        val local = LocalBand(base = band, clientId = "c", userId = "user-alice")

        class FakeSource(
            override var userId: String? = "user-alice",
            override var index: BandIndex? = BandIndex("user-alice"),
        ) : BandStore.Source {
            val locals = mutableMapOf<String, LocalBand>()
            override fun local(bandId: String) = locals[bandId]
        }

        expect("the index is written before the bands and only what is marked") {
            val store = InMemoryStore()
            val bandStore = BandStore(store, coroutineTestScope, clock = { 42 })
            val source = FakeSource().apply {
                locals["b1"] = local
                locals["b2"] = local.copy(base = band.copy(id = "b2"))
            }
            bandStore.bind(source)
            bandStore.markDirty("b1", flushSoon = false)
            bandStore.markIndexDirty()
            testScheduler.advanceUntilIdle()
            val written = setOf(BandStore.indexKey("user-alice"), BandStore.bandKey("user-alice", "b1"))
            withClue("b2 was never marked") { store.values.keys shouldBe written }
            val raw = store.values.getValue(BandStore.bandKey("user-alice", "b1"))
            KapeluszJson.decodeFromString(LocalBand.serializer(), raw) shouldBe local
        }

        expect("nothing is written without a user or after forgetting") {
            val store = InMemoryStore()
            val bandStore = BandStore(store, coroutineTestScope)
            val source = FakeSource(userId = null).apply { locals["b1"] = local }
            bandStore.bind(source)
            bandStore.markDirty("b1", flushSoon = false)
            withClue("nothing to do yet is not a failure") { bandStore.flush() shouldBe true }
            store.values.shouldBeEmpty()
            source.userId = "user-alice"
            bandStore.forgetUnwritten()
            bandStore.flush() shouldBe true
            withClue("the mark went with the previous user") { store.values.shouldBeEmpty() }
        }

        expect("removing drops the mark and the file") {
            val store = InMemoryStore()
            val bandStore = BandStore(store, coroutineTestScope)
            val source = FakeSource().apply { locals["b1"] = local }
            bandStore.bind(source)
            bandStore.markDirty("b1", flushSoon = false)
            bandStore.flush() shouldBe true
            bandStore.hasBand("user-alice", "b1") shouldBe true
            source.locals.remove("b1")
            bandStore.markDirty("b1", flushSoon = false)
            bandStore.remove("user-alice", "b1")
            bandStore.hasBand("user-alice", "b1") shouldBe false
            bandStore.flush() shouldBe true
            withClue("not written back") { store.read(BandStore.bandKey("user-alice", "b1")).shouldBeNull() }
        }

        expect("backups and unsent changes go under keys of their own with the time") {
            val store = InMemoryStore()
            val bandStore = BandStore(store, coroutineTestScope, clock = { 1_000 })
            bandStore.backupBand("user-alice", "b1", "{broken") shouldBe "band-user-alice-b1-backup-1000"
            store.read("band-user-alice-b1-backup-1000") shouldBe "{broken"
            bandStore.backupIndex("user-alice", "{index")
            store.read("band-index-user-alice-backup-1000") shouldBe "{index"
            bandStore.keepUnsent("user-alice", "b1", local)
            withClue("nothing unsent: nothing kept") { store.read("band-user-alice-b1-unsent-1000").shouldBeNull() }
        }
    })
