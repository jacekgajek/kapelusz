package com.jacekgajek.artsplit.app.data

import com.jacekgajek.artsplit.model.ArtSplitJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Android: download and install new versions automatically. */
    val autoUpdate: Boolean = true,
)

class SettingsRepository(private val store: KeyValueStore, private val scope: CoroutineScope) {
    private val _settings = MutableStateFlow(AppSettings())
    private val writeLock = Mutex()
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        // Unreadable settings are not worth keeping: fall back to the defaults.
        store.read(KEY)?.let { raw ->
            runCatching { ArtSplitJson.decodeFromString(AppSettings.serializer(), raw) }.onSuccess { _settings.value = it }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        // Writes run one at a time and always save the latest settings, so a slower earlier write never wins.
        scope.launch {
            writeLock.withLock { store.write(KEY, ArtSplitJson.encodeToString(AppSettings.serializer(), _settings.value)) }
        }
    }

    companion object {
        const val KEY = "settings"
    }
}
