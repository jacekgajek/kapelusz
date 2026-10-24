package com.jacekgajek.kapelusz.app.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/** A zoomed PDF reaches its bottom (ITERATION_5 §4.3), also a single page the list cannot scroll at all. */
class PdfZoomTest {
    private val size = IntSize(1000, 2000)

    /** A page list scrolled to [position], which can go from 0 to [end] (unscaled pixels); returns what it took. */
    private class FakeList(val end: Float) {
        var position = 0f
        fun scroll(delta: Float): Float {
            val next = (position + delta).coerceIn(0f, end)
            return (next - position).also { position = next }
        }
    }

    @Test
    fun aSinglePageZoomedInMovesAllTheWayDownAndBack() {
        val list = FakeList(end = 0f)
        val zoom = PdfZoom(list::scroll)
        zoom.transform(2f, Offset.Zero, Offset(500f, 0f), size)
        assertEquals(2f, zoom.scale)
        zoom.moveVertically(-1500f, 2000f)
        assertEquals(-1500f, zoom.offsetY, "the list cannot scroll: the view moves")
        zoom.moveVertically(-1000f, 2000f)
        assertEquals(-2000f, zoom.offsetY, "down to the page's bottom edge, not past it")
        zoom.moveVertically(2500f, 2000f)
        assertEquals(0f, zoom.offsetY)
    }

    @Test
    fun manyPagesZoomedInReachTheLastPagesBottom() {
        val list = FakeList(end = 3000f)
        val zoom = PdfZoom(list::scroll)
        zoom.transform(2f, Offset.Zero, Offset(500f, 0f), size)
        zoom.moveVertically(-10_000f, 2000f)
        assertEquals(3000f, list.position, "the list to its end first")
        assertEquals(-2000f, zoom.offsetY, "then the view: the bottom of the last page shows")
        zoom.moveVertically(3000f, 2000f)
        assertEquals(0f, zoom.offsetY, "back up: the view first")
        assertEquals(2500f, list.position, "then the list")
    }

    @Test
    fun zoomingOutGivesTheWayBackToTheList() {
        val list = FakeList(end = 3000f)
        val zoom = PdfZoom(list::scroll)
        zoom.toggle(Offset(500f, 0f), size)
        zoom.moveVertically(-8000f, 2000f)
        zoom.toggle(Offset(500f, 1000f), size)
        assertEquals(1f, zoom.scale)
        assertEquals(0f, zoom.offsetY)
        assertEquals(3000f, list.position)
    }

    @Test
    fun aSidewaysSwipeIsThePagersOnlyAtTheSideAlreadyInView() {
        val zoom = PdfZoom(FakeList(end = 3000f)::scroll)
        zoom.transform(2f, Offset.Zero, Offset(500f, 0f), size)
        assertEquals(false, zoom.atEdge(-10f, size))
        zoom.transform(1f, Offset(10_000f, 0f), Offset(500f, 0f), size)
        assertEquals(true, zoom.atEdge(10f, size), "the left side shows: a swipe right brings the previous attachment")
        assertEquals(false, zoom.atEdge(-10f, size))
    }
}
