package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.domain.PlayedPlaces

/**
 * A map of [places], one marker each; a marker's bubble opens [onPlace] (ITERATION_7 §8). Only where
 * [mapPickerAvailable]. [bottomInset]: covered by a panel, kept clear of controls and markers.
 */
@Composable
expect fun PlacesMap(places: List<PlayedPlaces.Place>, onPlace: (PlayedPlaces.Place) -> Unit, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp)
