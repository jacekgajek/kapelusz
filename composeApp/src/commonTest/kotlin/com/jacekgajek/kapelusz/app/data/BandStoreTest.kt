package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.model.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The store's own rules ([BandStore]): what is written when, under which keys, and what a mark means. */
class BandStoreTest {
    private val band = Band.create("b1", "Band", "PLN", "alice", "Alice", "user-alice")
    private val local = LocalBand(base = band, clientId = "c", userId = "user-alice")

    private class FakeSource(
        override var userId: String? = "user-alice",
        override var index: BandIndex? = BandIndex("user-alice"),
    ) : BandStore.Source {
        val locals = mutableMapOf<String, LocalBand>()
        override fun local(bandId: String) = locals[bandId]
    }

    @Test
    fun theIndexIsWrittenBeforeTheBandsAndOnlyWhatIsMarked() = runTest {
        val store = InMemoryStore()
        val bandStore = BandStore(store, this, clock = { 42 })
        val source = FakeSource().apply {
            locals["b1"] = local
            locals["b2"] = local.copy(base = band.copy(id = "b2"))
        }
        bandStore.bind(source)
        bandStore.markDirty("b1", flushSoon = false)
        bandStore.markIndexDirty()
        testScheduler.advanceUntilIdle()
        val written = setOf(BandStore.indexKey("user-alice"), BandStore.bandKey("user-alice", "b1"))
        assertEquals(written, store.values.keys, "b2 was never marked")
        val raw = store.values.getValue(BandStore.bandKey("user-alice", "b1"))
        assertEquals(local, KapeluszJson.decodeFromString(LocalBand.serializer(), raw))
    }

    @Test
    fun nothingIsWrittenWithoutAUserOrAfterForgetting() = runTest {
        val store = InMemoryStore()
        val bandStore = BandStore(store, this)
        val source = FakeSource(userId = null).apply { locals["b1"] = local }
        bandStore.bind(source)
        bandStore.markDirty("b1", flushSoon = false)
        assertTrue(bandStore.flush(), "nothing to do yet is not a failure")
        assertTrue(store.values.isEmpty())
        source.userId = "user-alice"
        bandStore.forgetUnwritten()
        assertTrue(bandStore.flush())
        assertTrue(store.values.isEmpty(), "the mark went with the previous user")
    }

    @Test
    fun removingDropsTheMarkAndTheFile() = runTest {
        val store = InMemoryStore()
        val bandStore = BandStore(store, this)
        val source = FakeSource().apply { locals["b1"] = local }
        bandStore.bind(source)
        bandStore.markDirty("b1", flushSoon = false)
        assertTrue(bandStore.flush())
        assertTrue(bandStore.hasBand("user-alice", "b1"))
        source.locals.remove("b1")
        bandStore.markDirty("b1", flushSoon = false)
        bandStore.remove("user-alice", "b1")
        assertFalse(bandStore.hasBand("user-alice", "b1"))
        assertTrue(bandStore.flush())
        assertNull(store.read(BandStore.bandKey("user-alice", "b1")), "not written back")
    }

    @Test
    fun backupsAndUnsentChangesGoUnderKeysOfTheirOwnWithTheTime() = runTest {
        val store = InMemoryStore()
        val bandStore = BandStore(store, this, clock = { 1_000 })
        assertEquals("band-user-alice-b1-backup-1000", bandStore.backupBand("user-alice", "b1", "{broken"))
        assertEquals("{broken", store.read("band-user-alice-b1-backup-1000"))
        bandStore.backupIndex("user-alice", "{index")
        assertEquals("{index", store.read("band-index-user-alice-backup-1000"))
        bandStore.keepUnsent("user-alice", "b1", local)
        assertNull(store.read("band-user-alice-b1-unsent-1000"), "nothing unsent: nothing kept")
    }
}
