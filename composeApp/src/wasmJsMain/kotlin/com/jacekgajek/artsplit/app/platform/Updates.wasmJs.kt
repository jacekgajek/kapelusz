package com.jacekgajek.artsplit.app.platform

import androidx.compose.runtime.Composable

// The web app is always the deployed version; a reload is its update.
@Composable
actual fun rememberUpdateControls(): UpdateControls? = null
