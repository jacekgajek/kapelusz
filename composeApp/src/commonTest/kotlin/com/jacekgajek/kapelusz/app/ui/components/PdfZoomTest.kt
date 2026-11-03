package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

private val size = IntSize(1000, 2000)

/** A page list scrolled to [position], which can go from 0 to [end] (unscaled pixels); returns what it took. */
private class FakeList(val end: Float) {
    var position = 0f
    fun scroll(delta: Float): Float {
        val next = (position + delta).coerceIn(0f, end)
        return (next - position).also { position = next }
    }
}

/** A zoomed PDF reaches its bottom, also a single page the list cannot scroll at all. */
class PdfZoomTest :
    ExpectSpec({

        expect("a single page zoomed in moves all the way down and back") {
            val list = FakeList(end = 0f)
            val zoom = PdfZoom(list::scroll)
            zoom.transform(2f, Offset.Zero, Offset(500f, 0f), size)
            zoom.scale shouldBe 2f
            zoom.moveVertically(-1500f, 2000f)
            withClue("the list cannot scroll: the view moves") { zoom.offsetY shouldBe -1500f }
            zoom.moveVertically(-1000f, 2000f)
            withClue("down to the page's bottom edge, not past it") { zoom.offsetY shouldBe -2000f }
            zoom.moveVertically(2500f, 2000f)
            zoom.offsetY shouldBe 0f
        }

        expect("many pages zoomed in reach the last page's bottom") {
            val list = FakeList(end = 3000f)
            val zoom = PdfZoom(list::scroll)
            zoom.transform(2f, Offset.Zero, Offset(500f, 0f), size)
            zoom.moveVertically(-10_000f, 2000f)
            withClue("the list to its end first") { list.position shouldBe 3000f }
            withClue("then the view: the bottom of the last page shows") { zoom.offsetY shouldBe -2000f }
            zoom.moveVertically(3000f, 2000f)
            withClue("back up: the view first") { zoom.offsetY shouldBe 0f }
            withClue("then the list") { list.position shouldBe 2500f }
        }

        expect("zooming out gives the way back to the list") {
            val list = FakeList(end = 3000f)
            val zoom = PdfZoom(list::scroll)
            zoom.toggle(Offset(500f, 0f), size)
            zoom.moveVertically(-8000f, 2000f)
            zoom.toggle(Offset(500f, 1000f), size)
            zoom.scale shouldBe 1f
            zoom.offsetY shouldBe 0f
            list.position shouldBe 3000f
        }

        expect("a sideways swipe is the pager's only at the side already in view") {
            val zoom = PdfZoom(FakeList(end = 3000f)::scroll)
            zoom.transform(2f, Offset.Zero, Offset(500f, 0f), size)
            zoom.atEdge(-10f, size) shouldBe false
            zoom.transform(1f, Offset(10_000f, 0f), Offset(500f, 0f), size)
            withClue("the left side shows: a swipe right brings the previous attachment") { zoom.atEdge(10f, size) shouldBe true }
            zoom.atEdge(-10f, size) shouldBe false
        }
    })
