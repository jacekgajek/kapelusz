package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.domain.PlayedPlaces

// No map on the web (ITERATION_6 §3): the places are a list there (ITERATION_7 §2f).
@Composable
actual fun PlacesMap(places: List<PlayedPlaces.Place>, onPlace: (PlayedPlaces.Place) -> Unit, modifier: Modifier) {
}
