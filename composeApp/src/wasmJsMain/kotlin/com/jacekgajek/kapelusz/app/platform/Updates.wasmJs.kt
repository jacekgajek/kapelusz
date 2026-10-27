package com.jacekgajek.kapelusz.app.platform

// The web app is always the deployed version; a reload is its update.
private fun reload() {
    js("window.location.reload()")
}

actual fun appReloader(): (() -> Unit)? = ::reload

actual fun isAndroidApp(): Boolean = false

actual fun storeListing(): String? = null
