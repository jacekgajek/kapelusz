package com.jacekgajek.kapelusz.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.jacekgajek.kapelusz.app.AppConfig
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.close
import com.jacekgajek.kapelusz.app.generated.map_pick_hint
import com.jacekgajek.kapelusz.app.generated.map_pick_use
import com.jacekgajek.kapelusz.app.generated.map_satellite
import com.jacekgajek.kapelusz.domain.Places
import org.jetbrains.compose.resources.stringResource

actual val mapPickerAvailable: Boolean = AppConfig.HAS_MAPS_KEY

/** Where the map opens without a pin: Poland as a whole. */
private val POLAND = LatLng(52.0, 19.4)
private const val COUNTRY_ZOOM = 5.5f
private const val PIN_ZOOM = 15f

/** A tapped spot: its pin, and the place's name when a place on the map was tapped. */
private class Picked(val position: LatLng, val name: String?)

@Composable
actual fun MapPickerDialog(start: Pair<Double, Double>?, onPick: (latitude: Double, longitude: Double, name: String?) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(start?.let { Picked(LatLng(it.first, it.second), null) }) }
    // Satellite (with roads and names): a clearing in a forest is found by what it looks like.
    var satellite by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState {
        position = start?.let { CameraPosition.fromLatLngZoom(LatLng(it.first, it.second), PIN_ZOOM) } ?: CameraPosition.fromLatLngZoom(POLAND, COUNTRY_ZOOM)
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
                IconButton(onClick = { satellite = !satellite }) { Icon(Icons.Default.Layers, stringResource(Res.string.map_satellite)) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .navigationBarsPadding().padding(16.dp),
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
