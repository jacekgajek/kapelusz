package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.domain.PlayedPlaces

/** A map of [places], one marker each; a marker's bubble opens [onPlace] (ITERATION_7 §2f). Only where [mapPickerAvailable]. */
@Composable
expect fun PlacesMap(places: List<PlayedPlaces.Place>, onPlace: (PlayedPlaces.Place) -> Unit, modifier: Modifier = Modifier)
