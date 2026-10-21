package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.model.ArtSplitJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Per-device preferences. */
@Serializable
data class AppSettings(
    /** `null` follows the device language. */
    val language: String? = null,
    val onlyMyEvents: Boolean = false,
    val showArchived: Boolean = false,
    /** Drives the "Continue" card on the event list. */
    val lastEditedEventId: String? = null,
)

class SettingsRepository(private val store: KeyValueStore, private val scope: CoroutineScope) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        store.read(KEY)?.let { _settings.value = ArtSplitJson.decodeFromString(AppSettings.serializer(), it) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        scope.launch { store.write(KEY, ArtSplitJson.encodeToString(AppSettings.serializer(), updated)) }
    }

    companion object {
        const val KEY = "settings"
    }
}
