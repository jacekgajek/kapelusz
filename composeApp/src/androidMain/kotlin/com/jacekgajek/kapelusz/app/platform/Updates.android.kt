package com.jacekgajek.kapelusz.app.platform

actual fun appReloader(): (() -> Unit)? = null

actual fun isAndroidApp(): Boolean = true

actual fun storeListing(): String? = PLAY_LISTING
