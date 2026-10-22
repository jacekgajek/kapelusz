package com.jacekgajek.kapelusz.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/** Supported UI languages; `null` in settings means "follow the device". */
val SUPPORTED_LANGUAGES = listOf("en", "pl")

/**
 * Overrides the locale used by Compose resources (the in-app language setting).
 * Platform implementations follow the Compose Multiplatform "change locale in app" recipe.
 */
expect object LocalAppLocale {
    val current: String @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}
