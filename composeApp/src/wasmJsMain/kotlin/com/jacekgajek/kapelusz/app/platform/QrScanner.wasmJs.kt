package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable

/** No scanner on the web: a phone's own camera opens the invite link anyway. */
@Composable
actual fun rememberQrScanner(): (suspend () -> String?)? = null
