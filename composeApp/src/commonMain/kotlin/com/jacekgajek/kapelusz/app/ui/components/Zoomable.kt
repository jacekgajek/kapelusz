package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize

/** How far a [zoomable] is zoomed in and moved. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Zooms by [zoom] around [centroid] and moves by [pan], kept so the content still covers its box. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize) {
        if (!zoom.isFinite() || !pan.isValid() || !centroid.isValid()) return
        val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val center = Offset(size.width / 2f, size.height / 2f)
        // The point under the fingers stays under them.
        val moved = offset + (centroid - center - offset) * (1 - next / scale) + pan
        scale = next
        offset = clamp(moved, size)
    }

    /** A double tap: in to [DOUBLE_TAP_SCALE] at [tap], or back to the whole. */
    fun toggle(tap: Offset, size: IntSize) {
        if (scale > 1f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            val center = Offset(size.width / 2f, size.height / 2f)
            scale = DOUBLE_TAP_SCALE
            offset = clamp((center - tap) * (DOUBLE_TAP_SCALE - 1), size)
        }
    }

    private fun clamp(value: Offset, size: IntSize): Offset {
        val maxX = size.width * (scale - 1) / 2
        val maxY = size.height * (scale - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    private companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

/**
 * Pinch to zoom, drag to move while zoomed in, double tap to zoom in or back (ITERATION_5 §4.3). One finger at the
 * whole size is left alone, so the pages around still scroll and swipe.
 */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.toggle(it, size) }) }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                // The lift has no finger down any more: no centre to zoom around (it would be NaN, and the picture gone).
                val centroid = event.calculateCentroid(useCurrent = true)
                if (fingers > 0 && centroid.isSpecified && (fingers >= 2 || state.scale > 1f)) {
                    state.transform(event.calculateZoom(), event.calculatePan(), centroid, size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }
