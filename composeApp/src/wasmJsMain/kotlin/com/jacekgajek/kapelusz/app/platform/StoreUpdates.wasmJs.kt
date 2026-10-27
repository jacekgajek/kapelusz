package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable

// The web app is the deployed version; a reload is its update.
@Composable
actual fun rememberStoreUpdates(): StoreUpdates? = null
