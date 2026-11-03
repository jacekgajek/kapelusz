package com.jacekgajek.kapelusz.app.settings

import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope

private suspend fun loaded(stored: String): AppSettings {
    val repository = SettingsRepository(InMemoryStore(mapOf(SettingsRepository.KEY to stored)), TestScope())
    repository.load()
    return repository.settings.value
}

class SettingsRepositoryTest :
    ExpectSpec({
        coroutineTestScope = true

        expect("an unknown language follows the device") {
            // E.g. stored by a newer version offering more languages: never a crash.
            // A setting from an older version (automatic updates of the website's APK) is ignored.
            loaded("""{"language":"de","autoUpdate":false}""") shouldBe AppSettings(language = null)
            loaded("""{"language":"pl"}""").language shouldBe "pl"
        }

        expect("unreadable settings fall back to the defaults") {
            loaded("not json") shouldBe AppSettings()
        }
    })
