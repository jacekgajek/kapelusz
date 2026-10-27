package com.jacekgajek.kapelusz.app.updates

import java.util.Locale

/**
 * Texts of update notifications. They are shown outside Compose (from a background job), where string resources
 * are not at hand. The language follows the default locale: the app's choice while it runs, otherwise the system's.
 */
internal class Strings(language: String?) {
    // The app's language when it is set (ITERATION_3 R7), otherwise the default locale.
    private val polish = (language ?: Locale.getDefault().language) == "pl"

    fun channelName() = if (polish) "Aktualizacje" else "Updates"

    fun readyTitle(versionName: String) = if (polish) "Kapelusz $versionName jest gotowy" else "Pass the Hat $versionName is ready"

    fun readyText() = if (polish) "Dotknij, aby otworzyć aplikację i zainstalować." else "Tap to open the app and install it."
}
