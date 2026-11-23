package io.github.xiaomibutton.remapper.backscreen.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import java.util.concurrent.TimeUnit
import kotlin.math.min

@Composable
internal fun LensRings(s: DashboardStore, locked: Boolean, ambient: Boolean = false) {
    if (!s.flag("lensRings")) return
    for (i in 1..2) {
        val window = s.value("ring$i", "Session")
        val metric = s.value("metric$i", "Usage")
        if (window == "Hidden" || locked && window != "Battery") continue
        if (window != "Battery" &&
            s.value("usageFile$i").isBlank() &&
            s.value("usageUrl$i").isBlank())
            continue
        val ring by
            reading(listOf(window, metric, s.value("usageUrl$i"), s.value("usageFile$i")), 60000) {
                if (window == "Battery") RingReading(battery(s.context).toFloat(), null)
                else usage(s, i)
            }
        val ink = ink(s, "Ring $i", MaterialTheme.colorScheme.primary)
        val v = ring?.getOrNull() ?: continue
        val fraction =
            if (metric == "Until reset" && v.reset != null) {
                val period =
                    if (window == "Session") TimeUnit.HOURS.toMillis(5)
                    else TimeUnit.DAYS.toMillis(7)
                ((v.reset - System.currentTimeMillis()).toFloat() / period).coerceIn(0f, 1f)
            } else v.percent / 100f
        val companion = s.flag("companion$i", true)
        val style = s.value("mascot$i", if (i == 1) "Sprout" else "Firefly")
        val swing =
            if (companion && !ambient && s.flag("companionMotion", true))
                rememberInfiniteTransition(label = "Companion")
                    .animateFloat(
                        -1f,
                        1f,
                        infiniteRepeatable(tween(1900), RepeatMode.Reverse),
                        label = "Sway",
                    )
            else null
        Canvas(Modifier.fillMaxSize()) {
            val x =
                size.width * (s.value("lensX", "16").toFloatOrNull() ?: 16f).coerceIn(4f, 32f) /
                    100f
            val y =
                size.height *
                    (s.value("lensY$i", if (i == 1) "25" else "75").toFloatOrNull()
                            ?: (if (i == 1) 25f else 75f))
                        .coerceIn(5f, 95f) / 100f
            val radius =
                min(size.width, size.height) *
                    (s.value("lensRadius", "20").toFloatOrNull() ?: 20f).coerceIn(4f, 24f) / 100f
            val offset = Offset(x - radius, y - radius)
            val bounds = Size(2 * radius, 2 * radius)
            drawArc(ink.copy(alpha = .20f), 0f, 360f, false, offset, bounds, style = Stroke(4f))
            drawArc(
                ink,
                -90f,
                360 * fraction,
                false,
                offset,
                bounds,
                style = Stroke(5f, cap = StrokeCap.Round),
            )
            if (companion) {
                val angle = -Math.PI * .34 + (swing?.value ?: 0f) * .06
                val at =
                    Offset(
                        x + radius * kotlin.math.cos(angle).toFloat(),
                        y + radius * kotlin.math.sin(angle).toFloat(),
                    )
                drawCompanion(style, at, (radius * .20f).coerceIn(5f, 22f), ink, v.percent >= 75f)
            }
        }
    }
}
