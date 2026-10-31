package com.jacekgajek.kapelusz.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class SettingsRepositoryTest {
    private suspend fun loaded(stored: String): AppSettings {
        val repository = SettingsRepository(InMemoryStore(mapOf(SettingsRepository.KEY to stored)), TestScope())
        repository.load()
        return repository.settings.value
    }

    @Test
    fun anUnknownLanguageFollowsTheDevice() = runTest {
        // E.g. stored by a newer version offering more languages: never a crash (ITERATION_3 R13).
        // A setting from an older version (automatic updates of the website's APK) is ignored.
        assertEquals(AppSettings(language = null), loaded("""{"language":"de","autoUpdate":false}"""))
        assertEquals("pl", loaded("""{"language":"pl"}""").language)
    }

    @Test
    fun unreadableSettingsFallBackToTheDefaults() = runTest {
        assertEquals(AppSettings(), loaded("not json"))
    }
}
