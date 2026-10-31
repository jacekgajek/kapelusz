package com.jacekgajek.kapelusz.app.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** The web storage in a real browser's IndexedDB. Runs in headless Chrome. */
class IndexedDbStoreTest {
    private val store = IndexedDbStore()

    @Test
    fun writesReadsAndRemoves() = runTest {
        assertNull(store.read("test-missing"))
        store.write("test-key", "one")
        assertEquals("one", store.read("test-key"))
        store.write("test-key", "two")
        assertEquals("two", store.read("test-key"), "a write replaces the value")
        store.remove("test-key")
        assertNull(store.read("test-key"))
        store.remove("test-key") // removing what is gone is no error
    }

    @Test
    fun keepsLargeAndUnicodeValues() = runTest {
        // A band file can be large, and names are Polish.
        val value = "Zażółć gęślą jaźń 🎺 ".repeat(50_000)
        store.write("test-large", value)
        assertEquals(value, IndexedDbStore().read("test-large"), "also through another store instance (the shared connection)")
        store.remove("test-large")
    }

    @Test
    fun keysAreSeparate() = runTest {
        store.write("test-a", "a")
        store.write("test-b", "b")
        assertEquals("a", store.read("test-a"))
        assertEquals("b", store.read("test-b"))
        store.remove("test-a")
        assertEquals("b", store.read("test-b"))
        store.remove("test-b")
    }
}
