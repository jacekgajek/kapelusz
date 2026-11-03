package com.jacekgajek.kapelusz.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.jacekgajek.kapelusz.app.ui.components.ZoomState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

private val size = IntSize(1000, 2000)

/** The viewer's zoom: bounded, kept on the picture, and never broken by a missing centre. */
class ZoomStateTest :
    ExpectSpec({

        expect("a missing centre changes nothing") {
            val zoom = ZoomState()
            zoom.transform(2f, Offset.Zero, Offset(500f, 1000f), size)
            // The lift of the fingers: no centre (NaN). It used to make the picture vanish.
            zoom.transform(1f, Offset.Zero, Offset.Unspecified, size)
            zoom.transform(Float.NaN, Offset.Zero, Offset(1f, 1f), size)
            zoom.scale shouldBe 2f
            zoom.offset shouldBe Offset.Zero
        }

        expect("zoom stays between whole and five times and the picture covers its box") {
            val zoom = ZoomState()
            zoom.transform(0.5f, Offset.Zero, Offset(500f, 1000f), size)
            withClue("not smaller than the whole") { zoom.scale shouldBe 1f }
            zoom.transform(100f, Offset.Zero, Offset(500f, 1000f), size)
            zoom.scale shouldBe 5f
            zoom.transform(1f, Offset(100_000f, -100_000f), Offset(500f, 1000f), size)
            withClue("moved only as far as the edges") { zoom.offset shouldBe Offset(2000f, -4000f) }
        }

        expect("double tap zooms in and back") {
            val zoom = ZoomState()
            zoom.toggle(Offset(500f, 1000f), size)
            zoom.scale shouldBe 2.5f
            zoom.toggle(Offset(0f, 0f), size)
            zoom.scale shouldBe 1f
            zoom.offset shouldBe Offset.Zero
        }

        expect("a sideways swipe is the pager's only at the side already in view") {
            val zoom = ZoomState()
            zoom.transform(2f, Offset.Zero, Offset(500f, 1000f), size)
            withClue("zoomed into the middle: a swipe moves the picture") { zoom.atEdge(-10f, size) shouldBe false }
            zoom.atEdge(10f, size) shouldBe false
            zoom.transform(1f, Offset(-10_000f, 0f), Offset(500f, 1000f), size)
            withClue("the right side shows: a swipe left brings the next attachment") { zoom.atEdge(-10f, size) shouldBe true }
            withClue("a swipe right still moves the picture back") { zoom.atEdge(10f, size) shouldBe false }
        }
    })
