package com.jacekgajek.kapelusz.app.storage

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The web storage in a real browser's IndexedDB. Runs in headless Chrome. */
class IndexedDbStoreTest :
    ExpectSpec({
        // The browser's own async work (IndexedDB) completes on its own, in real time; the test scope only skips `delay`s,
        // which this test has none of, as runTest did before.
        coroutineTestScope = true
        // Every test uses keys of its own and removes them; the store holds no other state.
        val store = IndexedDbStore()

        expect("writes, reads and removes") {
            store.read("test-missing").shouldBeNull()
            store.write("test-key", "one")
            store.read("test-key") shouldBe "one"
            store.write("test-key", "two")
            withClue("a write replaces the value") { store.read("test-key") shouldBe "two" }
            store.remove("test-key")
            store.read("test-key").shouldBeNull()
            store.remove("test-key") // removing what is gone is no error
        }

        expect("keeps large and unicode values") {
            // A band file can be large, and names are Polish.
            val value = "Zażółć gęślą jaźń 🎺 ".repeat(50_000)
            store.write("test-large", value)
            withClue("also through another store instance (the shared connection)") { IndexedDbStore().read("test-large") shouldBe value }
            store.remove("test-large")
        }

        expect("keys are separate") {
            store.write("test-a", "a")
            store.write("test-b", "b")
            store.read("test-a") shouldBe "a"
            store.read("test-b") shouldBe "b"
            store.remove("test-a")
            store.read("test-b") shouldBe "b"
            store.remove("test-b")
        }
    })
