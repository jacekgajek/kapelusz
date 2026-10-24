package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.ui.SUPPORTED_LANGUAGES
import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/** Per-device preferences. */
@Serializable
data class AppSettings(
    /** `null` follows the device language. */
    val language: String? = null,
    val onlyMyEvents: Boolean = false,
    val showArchived: Boolean = false,
    /** Android: download and install new versions automatically. */
    val autoUpdate: Boolean = true,
    /**
     * Android: gig reminders (ITERATION_2 §5.2), [reminderDays] before the event at [reminderTime]. Off until chosen
     * (ITERATION_4 §1.3): only the reminder about unsettled business is on by default.
     */
    val remindersEnabled: Boolean = false,
    val reminderDays: Set<Int> = setOf(1),
    val reminderTime: LocalTime = LocalTime(18, 0),
    /** Android: notifications about transfers others recorded with you (ITERATION_2 §5.3). */
    val transferNotifications: Boolean = false,
    /** Android: reminders to the payer about money still to send after a gig, at [reminderTime] (ITERATION_3 §2.2). */
    val debtReminders: Boolean = true,
    /** The one-time "get a reminder before your gigs?" prompt was answered. */
    val notificationPromptDone: Boolean = false,
    /** The one-time "remind you of money to pass on?" prompt was answered (V5-M1 D3). */
    val debtPromptDone: Boolean = false,
    /**
     * The page each event (`band/event`) was last left on, when not Gig (ITERATION_4 §2.4, ITERATION_5 §4.4); every
     * other event opens on Gig. The most recent last, at most [MAX_EVENT_TABS].
     */
    val eventTabs: Map<String, EventTab> = emptyMap(),
) {
    fun eventTab(bandId: String, eventId: String): EventTab = eventTabs["$bandId/$eventId"] ?: EventTab.GIG

    fun withEventTab(bandId: String, eventId: String, tab: EventTab): AppSettings {
        val key = "$bandId/$eventId"
        val others = eventTabs - key
        if (tab == EventTab.GIG) return copy(eventTabs = others)
        return copy(eventTabs = (others.entries.map { it.key to it.value } + (key to tab)).takeLast(MAX_EVENT_TABS).toMap())
    }

    companion object {
        const val MAX_EVENT_TABS = 200
    }
}

/** The event screen's pages (ITERATION_5 §4.4): what the gig is, the money that came in and goes out, and who gets what. */
@Serializable
enum class EventTab { GIG, MONEY, SETTLEMENT }

class SettingsRepository(private val store: KeyValueStore, private val scope: CoroutineScope) {
    private val _settings = MutableStateFlow(AppSettings())
    private val writeLock = Mutex()
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        // Unreadable settings are not worth keeping: fall back to the defaults.
        store.read(KEY)?.let { raw ->
            runCatching { KapeluszJson.decodeFromString(AppSettings.serializer(), raw) }.onSuccess { stored ->
                // A language this version does not offer (e.g. stored by a newer one) follows the device (ITERATION_3 R13).
                _settings.value = stored.copy(language = stored.language?.takeIf { it in SUPPORTED_LANGUAGES })
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        // Writes run one at a time and always save the latest settings, so a slower earlier write never wins.
        scope.launch {
            writeLock.withLock { store.write(KEY, KapeluszJson.encodeToString(AppSettings.serializer(), _settings.value)) }
        }
    }

    companion object {
        const val KEY = "settings"
    }
}
