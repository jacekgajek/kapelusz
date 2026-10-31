package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * The PDF view's zoom (ITERATION_5 §4.3): the page list is scaled from its top, so up and down are the list's own
 * scrolling (in its unscaled pixels, through [scrollList], which returns what it could take) plus [offsetY] for what
 * the list cannot reach: zoomed in, the list ends while the bottom of the document is still below the screen (a
 * single page cannot scroll at all), so the view itself moves the rest of the way. Sideways is [offsetX].
 */
internal class PdfZoom(private val scrollList: (Float) -> Float) : ZoomTarget {
    override var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set

    /** How far the scaled view is moved up past the list's end; 0 or less. */
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** Moves the document by [dy] screen pixels (a finger moving down: positive) within the [height] shown. */
    fun moveVertically(dy: Float, height: Float) {
        val maxY = height * (scale - 1)
        if (dy < 0) {
            // Further down the document: the list first, then the view for what the list could not take.
            val taken = scrollList(-dy / scale)
            offsetY = (offsetY + dy + taken * scale).coerceIn(-maxY, 0f)
        } else {
            // Back up: the view first, then the list.
            val back = minOf(dy, -offsetY)
            offsetY += back
            val rest = dy - back
            if (rest > 0f) scrollList(-rest / scale)
        }
    }

    /** Zooms by [zoom] around [centroid] (it stays under the fingers) and moves by [pan], in a view of [size]. */
    override fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize) {
        val old = scale
        val next = (old * zoom).coerceIn(1f, MAX_PDF_SCALE)
        // The document point under the centroid stays there: the view's own move scales along, the list makes up the rest.
        val nextOffsetY = offsetY * (next / old)
        val listDelta = (centroid.y - offsetY) / old - (centroid.y - nextOffsetY) / next
        scale = next
        offsetY = nextOffsetY
        val taken = scrollList(listDelta)
        offsetY = (offsetY - (listDelta - taken) * next).coerceIn(-size.height * (next - 1), 0f)
        moveVertically(pan.y, size.height.toFloat())
        val maxX = size.width * (next - 1) / 2
        offsetX = (offsetX + (centroid.x - size.width / 2f - offsetX) * (1 - next / old) + pan.x).coerceIn(-maxX, maxX)
    }

    override fun atEdge(dx: Float, size: IntSize): Boolean {
        val maxX = size.width * (scale - 1) / 2
        return if (dx > 0) offsetX >= maxX - EDGE_SLACK else offsetX <= -maxX + EDGE_SLACK
    }

    /** A double tap: in on [tap], or back to the whole width (at the same place in the document). */
    override fun toggle(tap: Offset, size: IntSize) {
        if (scale > 1f) {
            // The part above the screen that the view (not the list) was hiding: given back to the list.
            scrollList(-offsetY / scale)
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else {
            transform(DOUBLE_TAP_SCALE, Offset.Zero, tap, size)
        }
    }

    private companion object {
        const val MAX_PDF_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}
