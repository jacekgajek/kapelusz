package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.jacekgajek.kapelusz.domain.PlayedPlaces

// No map on the web (ITERATION_6 §3): the places are a list there (ITERATION_7 §8).
@Composable
actual fun PlacesMap(places: List<PlayedPlaces.Place>, onPlace: (PlayedPlaces.Place) -> Unit, modifier: Modifier, bottomInset: Dp) {
}
