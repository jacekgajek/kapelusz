package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable

/** Whether a place can be picked on a map (ITERATION_6 §3): Android with a Google Maps key; not on the web. */
expect val mapPickerAvailable: Boolean

/**
 * A full-screen map to pick a place on, e.g. in the middle of a forest (ITERATION_6 §3): a tap puts the pin (a place on
 * the map: its pin and name). Opens at [start] if given, else over Poland. [onPick] gets the pin and the place's name.
 */
@Composable
expect fun MapPickerDialog(start: Pair<Double, Double>?, onPick: (latitude: Double, longitude: Double, name: String?) -> Unit, onDismiss: () -> Unit)
