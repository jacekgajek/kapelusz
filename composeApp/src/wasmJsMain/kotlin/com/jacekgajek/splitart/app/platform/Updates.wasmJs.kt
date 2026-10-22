package com.jacekgajek.splitart.app.platform

import androidx.compose.runtime.Composable

// The web app is always the deployed version; a reload is its update.
@Composable
actual fun rememberUpdateControls(): UpdateControls? = null

private fun reload() {
    js("window.location.reload()")
}

actual fun appReloader(): (() -> Unit)? = ::reload

actual fun isAndroidApp(): Boolean = false
