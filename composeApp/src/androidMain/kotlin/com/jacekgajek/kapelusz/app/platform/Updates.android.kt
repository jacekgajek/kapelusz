package com.jacekgajek.kapelusz.app.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * How this Android app is distributed (ITERATION_7 §1.1), set by the app shell's variant before anything runs: the
 * website's APK brings its own updater, the Google Play build none, since Play updates it.
 */
object Distribution {
    /** The website's APK: its updater's controls (ITERATION_2 §1). Null in the Play build. */
    var selfUpdater: ((Context) -> UpdateControls)? = null
}

@Composable
actual fun rememberUpdateControls(): UpdateControls? {
    val context = LocalContext.current.applicationContext
    val updater = Distribution.selfUpdater ?: return null
    return remember(context) { updater(context) }
}

actual fun appReloader(): (() -> Unit)? = null

actual fun isAndroidApp(): Boolean = true

actual fun storeListing(): String? =
    if (Distribution.selfUpdater != null) null
    else AndroidPlatform.context?.let { "https://play.google.com/store/apps/details?id=${it.packageName}" }
