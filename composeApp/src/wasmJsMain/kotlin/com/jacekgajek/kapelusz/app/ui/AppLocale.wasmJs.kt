package com.jacekgajek.kapelusz.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale

/** Read by the `navigator.languages` override installed in index.html. */
private fun setCustomLocale(locale: String?): Unit = js("{ window.__customLocale = locale; }")

actual object LocalAppLocale {
    private val LocalLocale = staticCompositionLocalOf { Locale.current }

    actual val current: String
        @Composable get() = LocalLocale.current.toString()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        setCustomLocale(value?.replace('_', '-'))
        return LocalLocale.provides(Locale.current)
    }
}
