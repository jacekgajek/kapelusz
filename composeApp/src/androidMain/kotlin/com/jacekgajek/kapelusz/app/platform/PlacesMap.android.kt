package com.jacekgajek.kapelusz.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.played_concerts
import com.jacekgajek.kapelusz.domain.PlayedPlaces
import org.jetbrains.compose.resources.pluralStringResource

@Composable
actual fun PlacesMap(places: List<PlayedPlaces.Place>, onPlace: (PlayedPlaces.Place) -> Unit, modifier: Modifier, bottomInset: Dp) {
    // Opens roughly where the places are (Poland while none are known): Maps' own start, over the Americas, would
    // flash before the fit below.
    val camera = rememberCameraPositionState { position = roughly(places) }
    var loaded by remember { mutableStateOf(false) }
    // Fitted as places come in, until the user pans or zooms: then the map stays where they put it.
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(camera.isMoving) {
        if (camera.isMoving && camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) moved = true
    }
    // All of them in view once the map is laid out (bounds need its size); one place up close, not at the world's zoom.
    LaunchedEffect(loaded, places.map { it.latitude to it.longitude }) {
        if (!loaded || moved || places.isEmpty()) return@LaunchedEffect
        val update = if (places.size == 1) {
            CameraUpdateFactory.newLatLngZoom(LatLng(places[0].latitude, places[0].longitude), SINGLE_ZOOM)
        } else {
            val bounds = LatLngBounds.builder().apply { places.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
            CameraUpdateFactory.newLatLngBounds(bounds, EDGE_PX)
        }
        camera.move(update)
        // Places a few metres apart: not closer than one place alone.
        if (camera.position.zoom > SINGLE_ZOOM) camera.move(CameraUpdateFactory.zoomTo(SINGLE_ZOOM))
    }
    GoogleMap(modifier = modifier, cameraPositionState = camera, contentPadding = PaddingValues(bottom = bottomInset), onMapLoaded = { loaded = true }) {
        places.forEach { place -> key(place.latitude, place.longitude) {
            // Just pins: the text shows only for the one tapped.
            Marker(
                state = rememberUpdatedMarkerState(LatLng(place.latitude, place.longitude)),
                title = place.title,
                snippet = pluralStringResource(Res.plurals.played_concerts, place.events.size, place.events.size),
                onInfoWindowClick = { onPlace(place) },
            )
        } }
    }
}

private const val SINGLE_ZOOM = 15f
/** Room around the markers, the bottom one clear of the status panel over the map. */
private const val EDGE_PX = 160

/** Where the band played, before the map can be fitted to it exactly: the middle of the places, at a zoom for their spread. */
private fun roughly(places: List<PlayedPlaces.Place>): CameraPosition {
    if (places.isEmpty()) return CameraPosition.fromLatLngZoom(LatLng(52.0, 19.4), 5.5f)
    val latitudes = places.map { it.latitude }
    val longitudes = places.map { it.longitude }
    val spread = maxOf(latitudes.max() - latitudes.min(), longitudes.max() - longitudes.min())
    // The world is 360° wide at zoom 0, half that with each step; a little room around.
    val zoom = if (spread == 0.0) SINGLE_ZOOM else (kotlin.math.ln(360.0 / (spread * 1.5)) / kotlin.math.ln(2.0)).toFloat().coerceIn(2f, SINGLE_ZOOM)
    return CameraPosition.fromLatLngZoom(LatLng((latitudes.max() + latitudes.min()) / 2, (longitudes.max() + longitudes.min()) / 2), zoom)
}
