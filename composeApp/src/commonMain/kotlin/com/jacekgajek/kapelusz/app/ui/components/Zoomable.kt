package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Something that zooms and moves under the fingers: a picture ([ZoomState]) or a PDF's pages. */
interface ZoomTarget {
    val scale: Float

    /** Zooms by [zoom] around [centroid] and moves by [pan] (screen pixels, the fingers' way), in a view of [size]. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize)

    /** A double tap at [tap]. */
    fun toggle(tap: Offset, size: IntSize)

    /** Already at the side a finger moving sideways by [dx] would bring in: there is nothing more to see that way. */
    fun atEdge(dx: Float, size: IntSize): Boolean
}

/** Within this many pixels of a side counts as at it. */
internal const val EDGE_SLACK = 1f

/** How far a [zoomable] is zoomed in and moved. */
@Stable
class ZoomState : ZoomTarget {
    override var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Zooms by [zoom] around [centroid] and moves by [pan], kept so the content still covers its box. */
    override fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize) {
        if (!zoom.isFinite() || !pan.isValid() || !centroid.isValid()) return
        val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val center = Offset(size.width / 2f, size.height / 2f)
        // The point under the fingers stays under them.
        val moved = offset + (centroid - center - offset) * (1 - next / scale) + pan
        scale = next
        offset = clamp(moved, size)
    }

    /** A double tap: in to [DOUBLE_TAP_SCALE] at [tap], or back to the whole. */
    override fun atEdge(dx: Float, size: IntSize): Boolean {
        val maxX = size.width * (scale - 1) / 2
        return if (dx > 0) offset.x >= maxX - EDGE_SLACK else offset.x <= -maxX + EDGE_SLACK
    }

    override fun toggle(tap: Offset, size: IntSize) {
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
    .zoomGestures(state)
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

/**
 * The gestures of a [ZoomTarget], seen in [pass]. Two fingers zoom; one finger moves the zoomed-in content and, let
 * go while moving, carries on and slows down. One finger at the whole size is left to the list and pager around, and
 * so is a sideways swipe that starts at the side already in view, so the next attachment comes without zooming out.
 */
internal fun Modifier.zoomGestures(target: ZoomTarget, pass: PointerEventPass = PointerEventPass.Main): Modifier = this
    .pointerInput(target) { detectTapGestures(onDoubleTap = { target.toggle(it, size) }) }
    .pointerInput(target) {
        coroutineScope {
            var fling: Job? = null
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = pass)
                // A touch stops the glide.
                fling?.cancel()
                val velocity = VelocityTracker()
                var mine = false
                var theirs = false
                // One finger's way so far, until it is past the touch slop and clear whose the gesture is.
                var moved = Offset.Zero
                var lastFingers = 0
                do {
                    val event = awaitPointerEvent(pass)
                    val fingers = event.changes.count { it.pressed }
                    // The lift has no finger down any more: no centre to zoom around (it would be NaN, and the picture gone).
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (!theirs && fingers > 0 && centroid.isSpecified) {
                        var pan = event.calculatePan()
                        if (fingers >= 2) {
                            mine = true
                        } else if (target.scale <= 1f) {
                            // Pinched back to the whole: the finger left down is the list's and the pager's again.
                            mine = false
                        } else if (!mine) {
                            moved += pan
                            // A mouse is decided on its first move: the pager's slop for it is tiny and would win.
                            val slop = if (event.changes.any { it.type == PointerType.Mouse }) 0f else viewConfiguration.touchSlop
                            if (moved.getDistance() > slop) {
                                if (abs(moved.x) > abs(moved.y) && target.atEdge(moved.x, size)) {
                                    theirs = true
                                } else {
                                    mine = true
                                }
                                pan = moved
                            }
                        }
                        if (mine) {
                            target.transform(event.calculateZoom().takeIf { it.isFinite() } ?: 1f, pan, centroid, size)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            // Only one finger's moves count towards the glide: a finger lifted from a pinch makes the centre jump.
                            if (fingers != lastFingers) velocity.resetTracking()
                            if (fingers == 1) event.changes.firstOrNull { it.pressed }?.let { velocity.addPointerInputChange(it) }
                        }
                    }
                    // The lift itself counts: a finger held still before it is lifted has no speed left.
                    if (fingers == 0 && mine && lastFingers == 1) event.changes.firstOrNull { it.changedToUp() }?.let { velocity.addPointerInputChange(it) }
                    if (fingers > 0) lastFingers = fingers
                } while (event.changes.any { it.pressed })
                if (mine && lastFingers == 1 && target.scale > 1f) {
                    val speed = velocity.calculateVelocity()
                    fling = launch {
                        var done = Offset.Zero
                        Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(Offset(speed.x, speed.y), exponentialDecay()) {
                            target.transform(1f, value - done, Offset.Zero, size)
                            done = value
                        }
                    }
                }
            }
        }
    }
