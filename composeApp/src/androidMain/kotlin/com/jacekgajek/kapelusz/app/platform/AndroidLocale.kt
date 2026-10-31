package com.jacekgajek.kapelusz.app.platform

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** The device's locale, kept from before the first override. */
private var defaultLocale: Locale? = null

/** The in-app language on Android: the configuration's locale, which Compose resources read (the "change locale in app" recipe). */
@SuppressLint("AppBundleLocaleChanges")
@Composable
internal fun provideAndroidLocale(language: String?): ProvidedValue<*> {
    val configuration = LocalConfiguration.current
    if (defaultLocale == null) defaultLocale = Locale.getDefault()
    val locale = language?.let { Locale.forLanguageTag(it) } ?: defaultLocale!!
    Locale.setDefault(locale)
    configuration.setLocale(locale)
    val resources = LocalContext.current.resources
    @Suppress("DEPRECATION")
    resources.updateConfiguration(configuration, resources.displayMetrics)
    return LocalConfiguration.provides(configuration)
}
