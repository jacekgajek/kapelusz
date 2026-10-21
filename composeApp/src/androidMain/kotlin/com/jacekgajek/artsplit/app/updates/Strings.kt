package com.jacekgajek.artsplit.app.updates

import android.content.Context
import java.util.Locale

/**
 * Texts of update notifications. They are shown outside Compose (from a background job), where string resources
 * are not at hand; the language follows the app's (set as the default locale while it runs) or the system's.
 */
internal object Strings {
    private val polish get() = Locale.getDefault().language == "pl"

    fun channelName(context: Context) = if (polish) "Aktualizacje" else "Updates"

    fun readyTitle(context: Context, versionName: String) =
        if (polish) "Art Split $versionName jest gotowy" else "Art Split $versionName is ready"

    fun readyText(context: Context) = if (polish) "Dotknij, aby zainstalować aktualizację." else "Tap to install the update."
}
