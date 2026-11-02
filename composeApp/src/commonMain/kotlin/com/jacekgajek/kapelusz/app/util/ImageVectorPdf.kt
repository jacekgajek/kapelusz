package com.jacekgajek.kapelusz.app.util

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.conamobile.pdfkmp.vector.VectorImage

/**
 * pdfkmp only reads XML, so the vector is written out as an Android `<vector>` document and
 * parsed back; the parser does the relative-command, arc and group-transform maths.
 * Only solid brushes survive; gradients fall back to no paint.
 */
internal fun ImageVector.toVectorImage(): VectorImage = VectorImage.parse(
    buildString {
        append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        append(" android:width=\"${defaultWidth.value}dp\" android:height=\"${defaultHeight.value}dp\"")
        append(" android:viewportWidth=\"$viewportWidth\" android:viewportHeight=\"$viewportHeight\">")
        appendGroupChildren(root)
        append("</vector>")
    },
)

private fun StringBuilder.appendGroupChildren(group: VectorGroup) {
    for (child in group) appendNode(child)
}

private fun StringBuilder.appendNode(node: VectorNode) {
    when (node) {
        is VectorGroup -> appendGroup(node)
        is VectorPath -> appendPath(node)
    }
}

private fun StringBuilder.appendGroup(group: VectorGroup) {
    append("<group")
    append(" android:rotation=\"${group.rotation}\"")
    append(" android:pivotX=\"${group.pivotX}\" android:pivotY=\"${group.pivotY}\"")
    append(" android:scaleX=\"${group.scaleX}\" android:scaleY=\"${group.scaleY}\"")
    append(" android:translateX=\"${group.translationX}\" android:translateY=\"${group.translationY}\">")
    appendGroupChildren(group)
    append("</group>")
}

private fun StringBuilder.appendPath(path: VectorPath) {
    append("<path android:pathData=\"")
    path.pathData.forEach { append(it.toSvgCommand()).append(' ') }
    append('"')
    path.fill.toHexColor()?.let { append(" android:fillColor=\"$it\"") }
    path.stroke.toHexColor()?.let {
        append(" android:strokeColor=\"$it\" android:strokeWidth=\"${path.strokeLineWidth}\"")
    }
    append("/>")
}

private fun Brush?.toHexColor(): String? = (this as? SolidColor)?.value?.let { "#" + it.toArgb().toUInt().toString(16).padStart(8, '0') }

private fun Boolean.flag() = if (this) "1" else "0"

@Suppress("CyclomaticComplexMethod")
private fun PathNode.toSvgCommand(): String = when (this) {
    PathNode.Close -> "Z"

    is PathNode.MoveTo -> "M$x $y"

    is PathNode.RelativeMoveTo -> "m$dx $dy"

    is PathNode.LineTo -> "L$x $y"

    is PathNode.RelativeLineTo -> "l$dx $dy"

    is PathNode.HorizontalTo -> "H$x"

    is PathNode.RelativeHorizontalTo -> "h$dx"

    is PathNode.VerticalTo -> "V$y"

    is PathNode.RelativeVerticalTo -> "v$dy"

    is PathNode.CurveTo -> "C$x1 $y1 $x2 $y2 $x3 $y3"

    is PathNode.RelativeCurveTo -> "c$dx1 $dy1 $dx2 $dy2 $dx3 $dy3"

    is PathNode.ReflectiveCurveTo -> "S$x1 $y1 $x2 $y2"

    is PathNode.RelativeReflectiveCurveTo -> "s$dx1 $dy1 $dx2 $dy2"

    is PathNode.QuadTo -> "Q$x1 $y1 $x2 $y2"

    is PathNode.RelativeQuadTo -> "q$dx1 $dy1 $dx2 $dy2"

    is PathNode.ReflectiveQuadTo -> "T$x $y"

    is PathNode.RelativeReflectiveQuadTo -> "t$dx $dy"

    is PathNode.ArcTo ->
        "A$horizontalEllipseRadius $verticalEllipseRadius $theta " +
            "${isMoreThanHalf.flag()} ${isPositiveArc.flag()} $arcStartX $arcStartY"

    is PathNode.RelativeArcTo ->
        "a$horizontalEllipseRadius $verticalEllipseRadius $theta " +
            "${isMoreThanHalf.flag()} ${isPositiveArc.flag()} $arcStartDx $arcStartDy"
}
