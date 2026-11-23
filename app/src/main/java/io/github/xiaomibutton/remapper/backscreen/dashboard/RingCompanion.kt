package io.github.xiaomibutton.remapper.backscreen.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

internal val companionStyles = listOf("Sprout", "Firefly", "Cat")

/** Original miniature companions. The expression changes near the end of the selected allowance. */
internal fun DrawScope.drawCompanion(
    style: String,
    at: Offset,
    r: Float,
    color: Color,
    busy: Boolean,
) {
    val ink = Color(0xff352a26)
    val cream = Color(0xffffd6ab)
    translate(at.x, at.y) {
        when (style) {
            "Sprout" -> {
                drawLine(ink, Offset(0f, -.45f * r), Offset(.1f * r, -r), r * .12f, StrokeCap.Round)
                rotate(-24f, Offset.Zero) {
                    drawOval(Color(0xff87a87f), Offset(0f, -1.13f * r), Size(.72f * r, .37f * r))
                }
            }
            "Firefly" -> {
                for (side in listOf(-1f, 1f)) {
                    rotate(side * 28f, Offset.Zero) {
                        drawOval(cream, Offset(-.27f * r, -1.18f * r), Size(.54f * r, 1.05f * r))
                    }
                }
            }
            else -> {
                for (side in listOf(-1f, 1f)) {
                    val ear =
                        androidx.compose.ui.graphics.Path().apply {
                            moveTo(side * .72f * r, -.9f * r)
                            lineTo(side * .12f * r, -.44f * r)
                            lineTo(side * .74f * r, -.16f * r)
                            close()
                        }
                    drawPath(ear, color)
                }
            }
        }
        drawOval(color, Offset(-.78f * r, -.53f * r), Size(1.56f * r, 1.23f * r))
        drawOval(cream, Offset(-.54f * r, -.19f * r), Size(1.08f * r, .73f * r))
        for (side in listOf(-1f, 1f)) {
            val eye = Offset(side * .25f * r, .06f * r)
            if (busy)
                drawLine(
                    ink,
                    eye - Offset(.085f * r, 0f),
                    eye + Offset(.085f * r, 0f),
                    r * .10f,
                    StrokeCap.Round,
                )
            else drawCircle(ink, r * .085f, eye)
        }
    }
}

@Composable
internal fun CompanionBadge(style: String, color: Color, busy: Boolean) {
    Canvas(Modifier.size(28.dp)) {
        drawCompanion(style, center, size.minDimension * .40f, color, busy)
    }
}
