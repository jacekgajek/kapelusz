package com.jacekgajek.kapelusz.app.platform

// The Android app is installed; the web's install hints do not apply.
actual suspend fun webInstallState(): WebInstallState? = null
