package com.jacekgajek.kapelusz.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.map_pick_hint
import com.jacekgajek.kapelusz.app.generated.map_pick_use
import com.jacekgajek.kapelusz.app.generated.map_satellite
import com.jacekgajek.kapelusz.app.generated.played_concerts
import com.jacekgajek.kapelusz.text.Places
import com.jacekgajek.kapelusz.text.PlayedPlaces
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Where the map opens without a pin: Poland as a whole. */
private val POLAND = LatLng(52.0, 19.4)
private const val COUNTRY_ZOOM = 5.5f
private const val PIN_ZOOM = 15f

/** A tapped spot: its pin, and the place's name when a place on the map was tapped. */
private class Picked(val position: LatLng, val name: String?)

/** Google Maps through the Maps Compose library; only with a Maps key. */
object GoogleMaps : Maps {
    @Composable
    override fun PickerDialog(
        start: Pair<Double, Double>?,
        onPick: (latitude: Double, longitude: Double, name: String?) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var picked by remember { mutableStateOf(start?.let { Picked(LatLng(it.first, it.second), null) }) }
        // Satellite (with roads and names): a clearing in a forest is found by what it looks like.
        var satellite by remember { mutableStateOf(false) }
        val camera = rememberCameraPositionState {
            position = start?.let { CameraPosition.fromLatLngZoom(LatLng(it.first, it.second), PIN_ZOOM) }
                ?: CameraPosition.fromLatLngZoom(POLAND, COUNTRY_ZOOM)
        }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(mapType = if (satellite) MapType.HYBRID else MapType.NORMAL),
                    onMapClick = { picked = Picked(it, null) },
                    onPOIClick = { picked = Picked(it.latLng, it.name) },
                ) {
                    picked?.let { Marker(state = rememberUpdatedMarkerState(it.position), title = it.name) }
                }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(Res.string.close)) }
                    Text(stringResource(Res.string.map_pick_hint), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { satellite = !satellite }) {
                        Icon(Icons.Default.Layers, stringResource(Res.string.map_satellite))
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    val current = picked
                    if (current != null) {
                        Text(
                            Places.pinText(current.position.latitude, current.position.longitude, current.name),
                            Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        enabled = current != null,
                        onClick = { current?.let { onPick(it.position.latitude, it.position.longitude, it.name) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(Res.string.map_pick_use)) }
                }
            }
        }
    }

    @Composable
    override fun PlacesMap(
        places: List<PlayedPlaces.Place>,
        onPlace: (PlayedPlaces.Place) -> Unit,
        modifier: Modifier,
        bottomInset: Dp,
    ) = BoxWithConstraints(modifier) {
        // The fit is worked out from the map's own size, so the map opens on it: no first guess that the camera then
        // corrects once the map has loaded (slow phones showed both).
        val view = DpSize(maxWidth, maxHeight - bottomInset)
        val camera = rememberCameraPositionState { position = fitted(places, view) }
        // Fitted as places come in, until the user pans or zooms: then the map stays where they put it.
        var moved by remember { mutableStateOf(false) }
        LaunchedEffect(camera.isMoving) {
            if (camera.isMoving && camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) moved = true
        }
        LaunchedEffect(view, places.map { it.latitude to it.longitude }) {
            if (moved || places.isEmpty()) return@LaunchedEffect
            val target = fitted(places, view)
            if (target != camera.position) camera.move(CameraUpdateFactory.newCameraPosition(target))
        }
        val background = MaterialTheme.colorScheme.surface.toArgb()
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            contentPadding = PaddingValues(bottom = bottomInset),
            // Before the tiles are drawn: the screen's colour, not a white box (in dark mode most of all).
            googleMapOptionsFactory = { GoogleMapOptions().backgroundColor(background) },
        ) {
            places.forEach { place ->
                key(place.latitude, place.longitude) {
                    // Just pins: the text shows only for the one tapped.
                    Marker(
                        state = rememberUpdatedMarkerState(LatLng(place.latitude, place.longitude)),
                        title = place.title,
                        snippet = pluralStringResource(Res.plurals.played_concerts, place.events.size, place.events.size),
                        onInfoWindowClick = { onPlace(place) },
                    )
                }
            }
        }
    }
}

private const val SINGLE_ZOOM = 15f

/** Room around the markers (a pin is about 40 dp tall, drawn above its point). */
private val EDGE = 48.dp

/**
 * The camera that shows all [places] in a map of [size] (what the status panel leaves of it), as Maps' own fit to
 * bounds would, but without waiting for the map to load: Web Mercator, the world 256 dp wide at zoom 0, twice that with
 * each step. One place, or places a few metres apart: up close, not at the deepest zoom. None yet: Poland.
 */
private fun fitted(places: List<PlayedPlaces.Place>, size: DpSize): CameraPosition {
    if (places.isEmpty()) return CameraPosition.fromLatLngZoom(POLAND, COUNTRY_ZOOM)
    val (west, east) = longitudes(places.map { it.longitude })
    val north = mercator(places.maxOf { it.latitude })
    val south = mercator(places.minOf { it.latitude })
    val width = (size.width - EDGE * 2).value.coerceAtLeast(1f)
    val height = (size.height - EDGE * 2).value.coerceAtLeast(1f)
    // The world's width at zoom 0 is 256 dp for 360° of longitude, or 2π of Mercator y.
    val zoomX = if (east > west) log2(width / 256.0 * 360.0 / (east - west)) else Double.MAX_VALUE
    val zoomY = if (north > south) log2(height / 256.0 * 2 * PI / (north - south)) else Double.MAX_VALUE
    val zoom = minOf(zoomX, zoomY, SINGLE_ZOOM.toDouble()).coerceAtLeast(0.0).toFloat()
    val middle = (west + east) / 2
    return CameraPosition.fromLatLngZoom(LatLng(latitude((north + south) / 2), if (middle > 180) middle - 360 else middle), zoom)
}

/** West and east edges of the narrowest stretch holding all [longitudes]: across the date line if that is shorter (east may pass 180). */
private fun longitudes(longitudes: List<Double>): Pair<Double, Double> {
    val sorted = longitudes.sorted()
    // The widest gap between neighbours is what the view leaves out; around the back of the world by default.
    var west = sorted.first()
    var east = sorted.last()
    var gap = sorted.first() + 360 - sorted.last()
    for (i in 0 until sorted.size - 1) {
        if (sorted[i + 1] - sorted[i] > gap) {
            gap = sorted[i + 1] - sorted[i]
            west = sorted[i + 1]
            east = sorted[i] + 360
        }
    }
    return west to east
}

private fun mercator(latitude: Double) = ln(tan(PI / 4 + Math.toRadians(latitude) / 2))

private fun latitude(mercator: Double) = Math.toDegrees(2 * atan(exp(mercator)) - PI / 2)
