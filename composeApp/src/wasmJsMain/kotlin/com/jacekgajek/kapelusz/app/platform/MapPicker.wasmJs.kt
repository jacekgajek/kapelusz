package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable

// No map on the web (ITERATION_6 §3): Compose for the web has none, and Location's pin button is not shown.
actual val mapPickerAvailable: Boolean = false

@Composable
actual fun MapPickerDialog(start: Pair<Double, Double>?, onPick: (latitude: Double, longitude: Double, name: String?) -> Unit, onDismiss: () -> Unit) {
}
