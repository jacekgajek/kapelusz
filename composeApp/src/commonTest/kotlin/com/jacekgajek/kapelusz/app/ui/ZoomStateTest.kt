package com.jacekgajek.kapelusz.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.jacekgajek.kapelusz.app.ui.components.ZoomState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The viewer's zoom (ITERATION_5 §4.3): bounded, kept on the picture, and never broken by a missing centre. */
class ZoomStateTest {
    private val size = IntSize(1000, 2000)

    @Test
    fun aMissingCentreChangesNothing() {
        val zoom = ZoomState()
        zoom.transform(2f, Offset.Zero, Offset(500f, 1000f), size)
        // The lift of the fingers: no centre (NaN). It used to make the picture vanish.
        zoom.transform(1f, Offset.Zero, Offset.Unspecified, size)
        zoom.transform(Float.NaN, Offset.Zero, Offset(1f, 1f), size)
        assertEquals(2f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun zoomStaysBetweenWholeAndFiveTimesAndThePictureCoversItsBox() {
        val zoom = ZoomState()
        zoom.transform(0.5f, Offset.Zero, Offset(500f, 1000f), size)
        assertEquals(1f, zoom.scale, "not smaller than the whole")
        zoom.transform(100f, Offset.Zero, Offset(500f, 1000f), size)
        assertEquals(5f, zoom.scale)
        zoom.transform(1f, Offset(100_000f, -100_000f), Offset(500f, 1000f), size)
        assertEquals(Offset(2000f, -4000f), zoom.offset, "moved only as far as the edges")
    }

    @Test
    fun doubleTapZoomsInAndBack() {
        val zoom = ZoomState()
        zoom.toggle(Offset(500f, 1000f), size)
        assertEquals(2.5f, zoom.scale)
        zoom.toggle(Offset(0f, 0f), size)
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }
}
