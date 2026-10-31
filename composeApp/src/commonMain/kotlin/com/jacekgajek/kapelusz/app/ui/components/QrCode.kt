package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import qrcode.raw.QRCodeProcessor

/**
 * [text] as a QR code (I6 §8): the library only encodes; the squares are drawn here. Black on white whatever
 * the theme, with a white margin: scanners need the contrast and the quiet zone.
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier, size: Dp = 204.dp) {
    val squares = remember(text) { QRCodeProcessor(text).encode().map { row -> row.map { it.dark } } }
    Box(modifier.background(Color.White, RoundedCornerShape(12.dp)).semantics { contentDescription = description }) {
        Canvas(Modifier.size(size)) {
            // The quiet zone is four modules of white on every side (the encoder gives none), drawn as part of the grid.
            val cell = this.size.minDimension / (squares.size + 2 * QUIET_ZONE)
            squares.forEachIndexed { row, cells ->
                cells.forEachIndexed { column, dark ->
                    // A hair larger than the cell, so neighbours leave no seams.
                    if (dark) drawRect(Color.Black, Offset((column + QUIET_ZONE) * cell, (row + QUIET_ZONE) * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private const val QUIET_ZONE = 4
