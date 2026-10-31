package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/** Read by the `navigator.languages` override installed in index.html. */
private fun setCustomLocale(locale: String?): Unit = js("{ window.__customLocale = locale; }")

private val LocalLocale = staticCompositionLocalOf { Locale.current }

/** The in-app language on the web: told to the page's `navigator.languages` override, which Compose resources read. */
@Composable
internal fun provideWebLocale(language: String?): ProvidedValue<*> {
    setCustomLocale(language?.replace('_', '-'))
    return LocalLocale.provides(Locale.current)
}
