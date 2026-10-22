package com.jacekgajek.kapelusz.app.platform

// The web suggests the Android app among its install hints (webInstallState, ITERATION_3 §2.3); "Not now" there only
// lasts until the next visit to the event list, so nothing is stored.
actual suspend fun appDownload(): AppDownload? = null

actual fun dismissAppDownload(download: AppDownload) = Unit

