package com.jacekgajek.kapelusz.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.CameraUpdateFactory
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
actual fun PlacesMap(places: List<PlayedPlaces.Place>, onPlace: (PlayedPlaces.Place) -> Unit, modifier: Modifier) {
    val camera = rememberCameraPositionState()
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
    val density = LocalDensity.current.density
    GoogleMap(modifier = modifier, cameraPositionState = camera, onMapLoaded = { loaded = true }) {
        // Made inside the map: a bitmap descriptor needs Maps initialised.
        val upcomingPin = remember(density) { greyPin(density) }
        places.forEach { place -> key(place.latitude, place.longitude) {
            // Just pins: the text shows only for the one tapped. Red where we played, grey where we only will (§2f).
            Marker(
                state = rememberUpdatedMarkerState(LatLng(place.latitude, place.longitude)),
                title = place.title,
                snippet = pluralStringResource(Res.plurals.played_concerts, place.events.size, place.events.size),
                icon = if (place.upcoming) upcomingPin else null,
                onInfoWindowClick = { onPlace(place) },
            )
        } }
    }
}

private const val SINGLE_ZOOM = 15f
/** Room around the markers, the bottom one clear of the status panel over the map. */
private const val EDGE_PX = 160

/** The standard pin's droplet in grey, with its dot: a gig still to come. */
private fun greyPin(density: Float): BitmapDescriptor {
    val width = (27 * density).toInt()
    val height = (43 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = width / 2f
    val droplet = Path().apply {
        addCircle(radius, radius, radius - density, Path.Direction.CW)
        moveTo(radius * 0.25f, radius * 1.45f)
        lineTo(radius, height - density)
        lineTo(radius * 1.75f, radius * 1.45f)
        close()
    }
    canvas.drawPath(droplet, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt() })
    canvas.drawPath(droplet, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF616161.toInt(); style = Paint.Style.STROKE; strokeWidth = density })
    canvas.drawCircle(radius, radius, radius * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF424242.toInt() })
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
