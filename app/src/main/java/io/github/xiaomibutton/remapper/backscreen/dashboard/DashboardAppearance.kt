package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.util.Calendar
import kotlin.math.*
import kotlinx.coroutines.*

@Composable
internal fun ClockFace(s: DashboardStore, time: Long, dim: Boolean) {
    val col = ink(s, "Clock", if (dim) Color(0xffddb69e) else MaterialTheme.colorScheme.onSurface)
    if (s.value(if (dim) "dimFace" else "clockFace", "Digital") == "Analog") {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        Canvas(Modifier.size(if (dim) 60.dp else 72.dp)) {
            val r = size.minDimension * .46f
            fun point(angle: Double, length: Float) =
                center + Offset((sin(angle) * length).toFloat(), (-cos(angle) * length).toFloat())
            for (i in 0 until 12) drawLine(
                col.copy(alpha = .6f),
                point(i * PI / 6, r * .87f),
                point(i * PI / 6, r),
                2f,
            )
            fun hand(angle: Double, length: Float, width: Float) {
                drawLine(col, center, point(angle, length), width, StrokeCap.Round)
            }
            hand((c.get(Calendar.HOUR) + c.get(Calendar.MINUTE) / 60.0) * PI / 6, r * .54f, 5f)
            hand(c.get(Calendar.MINUTE) * PI / 30, r * .82f, 3.5f)
            if (!dim && s.flag("Seconds")) hand(c.get(Calendar.SECOND) * PI / 30, r * .91f, 1.8f)
            drawCircle(col, 3f)
        }
    } else {
        val h24 =
            when (s.value("format")) {
                "12 hour" -> false
                "24 hour" -> true
                else -> DateFormat.is24HourFormat(s.context)
            }
        val fmt =
            (if (h24) "HH:mm" else "h:mm") +
                (if (!dim && s.flag("Seconds")) ":ss" else "") +
                (if (h24) "" else " a")
        val font by
            produceState<FontFamily?>(null, s.value("font")) {
                val uri = s.value("font")
                if (uri.isNotBlank())
                    value =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                s.context.contentResolver
                                    .openFileDescriptor(Uri.parse(uri), "r")
                                    ?.use {
                                        FontFamily(Typeface.Builder(it.fileDescriptor).build())
                                    }
                            }
                                .getOrNull()
                        }
            }
        Text(
            DateFormat.format(fmt, time).toString(),
            color = col,
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = font,
        )
    }
}

@Composable
internal fun Scene(s: DashboardStore, faceDown: Boolean, tilt: Float) {
    val kind = s.value("scene", "Warm")
    val bg =
        if (kind == "Solid color") ink(s, "Background", MaterialTheme.colorScheme.surface)
        else MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.secondaryContainer
    val x = if (s.flag("Tilt parallax")) tilt * 16f else 0f
    Box(Modifier.fillMaxSize().background(bg).clipToBounds()) {
        val uri = s.value("backdrop")
        if (kind == "Photo" || kind == "Video") {
            val frame by
                produceState<Bitmap?>(
                    null,
                    uri,
                    kind,
                    s.value("playback"),
                    s.value("clipStart"),
                    s.value("clipEnd"),
                ) {
                    if (uri.isBlank()) return@produceState
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (kind == "Photo") value = decode(s.context, Uri.parse(uri))
                            else {
                                val meta = MediaMetadataRetriever()
                                try {
                                    meta.setDataSource(s.context, Uri.parse(uri))
                                    val duration =
                                        (meta
                                                .extractMetadata(
                                                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                                                )
                                                ?.toLongOrNull() ?: 1L)
                                            .coerceAtLeast(1)
                                    val a =
                                        ((s.value("clipStart").toDoubleOrNull() ?: 0.0) * 1000)
                                            .toLong()
                                            .coerceIn(0, duration - 1)
                                    val b =
                                        (s.value("clipEnd").toDoubleOrNull()?.times(1000)?.toLong()
                                                ?: duration)
                                            .coerceIn(a + 1, duration)
                                    val start = SystemClock.elapsedRealtime()
                                    val length = b - a
                                    val mode = s.value("playback", "Loop")
                                    val width =
                                        (meta
                                                .extractMetadata(
                                                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                                                )
                                                ?.toIntOrNull() ?: 640)
                                            .coerceAtLeast(1)
                                    val height =
                                        (meta
                                                .extractMetadata(
                                                    MediaMetadataRetriever
                                                        .METADATA_KEY_VIDEO_HEIGHT,
                                                )
                                                ?.toIntOrNull() ?: 400)
                                            .coerceAtLeast(1)
                                    val scale = 640.0 / max(width, height)
                                    while (isActive) {
                                        val passed = SystemClock.elapsedRealtime() - start
                                        val t =
                                            when (mode) {
                                                "Reverse" -> length - 1 - passed % length
                                                "Once" -> min(passed, length - 1)
                                                "Ping-pong" ->
                                                    if (passed / length % 2L == 0L) passed % length
                                                    else length - 1 - passed % length
                                                else -> passed % length
                                            }
                                        value =
                                            meta.getScaledFrameAtTime(
                                                (a + t) * 1000,
                                                MediaMetadataRetriever.OPTION_CLOSEST,
                                                (width * scale).toInt().coerceAtLeast(1),
                                                (height * scale).toInt().coerceAtLeast(1),
                                            )
                                        if (mode == "Once" && passed >= length) break
                                        delay(100)
                                    }
                                } finally {
                                    meta.release()
                                }
                            }
                        }
                    }
                }
            frame?.let {
                Image(
                    it.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = x
                        scaleX = 1.05f
                        scaleY = 1.05f
                    },
                    contentScale =
                        if (s.value("framing") == "Fit") ContentScale.Fit else ContentScale.Crop,
                    alpha = .25f,
                )
            }
        }
        val wanted = s.value("effect", "Off")
        val effect =
            if (wanted == "Automatic") {
                val w by reading(s.value("city"), 900000) { weather(s) }
                when (w?.getOrNull()?.code) {
                    in 51..69,
                    in 80..82 -> "Rain"
                    in 71..77,
                    85,
                    86 -> "Snow"
                    in 95..99 -> "Storm"
                    else -> "Off"
                }
            } else wanted
        val animate = kind in setOf("Silk", "Clay waves", "Soft metal") || effect != "Off"
        val phase =
            if (animate)
                rememberInfiniteTransition(label = "Weather and scene")
                    .animateFloat(
                        0f,
                        1f,
                        infiniteRepeatable(tween(10000, easing = LinearEasing)),
                        label = "Drift",
                    )
                    .value
            else 0f
        if (
            kind in setOf("Silk", "Clay waves", "Soft metal") &&
                android.os.Build.VERSION.SDK_INT >= 33
        )
            AnimatedBackdrop(kind, bg, accent, phase, x)
        Canvas(Modifier.fillMaxSize()) {
            if (kind == "Warm")
                drawRect(
                    Brush.linearGradient(listOf(soft.copy(alpha = .30f), bg.copy(alpha = .08f))),
                )
            if (kind == "Dark") drawRect(Color.Black.copy(alpha = .18f))
            if (effect != "Off")
                for (i in 1..36) {
                    val point =
                        Offset(
                            (i * .618f % 1f) * size.width,
                            ((i * .379f + phase * 3) % 1) * size.height,
                        )
                    if (effect == "Snow") drawCircle(accent.copy(alpha = .36f), 2f, point)
                    else drawLine(accent.copy(alpha = .28f), point, point + Offset(-4f, 11f), 1.6f)
                }
            if (effect == "Storm" && phase in 0.72f..0.74f) drawRect(Color.White.copy(alpha = .16f))
            if (s.flag("CRT lines")) {
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.Black.copy(alpha = .06f), Offset(0f, y), Offset(size.width, y))
                    y += 5f
                }
            }
            if (faceDown && s.flag("Table lights"))
                drawRect(
                    accent.copy(alpha = .55f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(8f),
                )
        }
    }
}

@Composable
internal fun AmbientFace(s: DashboardStore, locked: Boolean, wake: () -> Unit) {
    val now by
        produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(10000)
            }
        }
    val waiting =
        remember(now, locked) {
            if (
                locked &&
                    !io.github.xiaomibutton.remapper.backscreen.notifications.NotificationAccess
                        .lockScreen(s.context)
                        .showNotifications
            )
                emptyList()
            else notices(s).distinctBy { it.packageName }.take(4)
        }
    Column(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = wake)
            .then(
                if (waiting.isNotEmpty() && s.flag("Edge glow"))
                    Modifier.border(2.dp, Color(0xffb77958))
                else Modifier,
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClockFace(s, now, true)
        val fields = buildList {
            if (s.flag("AOD date", true)) add(DateFormat.format("EEE, d MMM", now).toString())
            if (s.flag("AOD battery")) add("${battery(s.context)}%")
        }
        Text(fields.joinToString(" · "), color = Color.LightGray)
        if (s.flag("AOD weather")) {
            val w by reading(s.value("city"), 900000) { weather(s) }
            w?.getOrNull()?.let { Text(it.label, color = Color.LightGray) }
        }
        if (s.flag("AOD badges") && waiting.isNotEmpty())
            Row {
                waiting.forEach { n ->
                    val icon =
                        remember(n.packageName) {
                            runCatching {
                                n.notification.smallIcon.loadDrawable(s.context)?.let {
                                    androidx.core.graphics.drawable.DrawableCompat.wrap(it)
                                }
                            }
                                .getOrNull()
                        }
                    icon?.let {
                        Image(
                            remember(it) { it.toBitmap(32, 32).asImageBitmap() },
                            appLabel(s, n.packageName),
                            Modifier.size(20.dp),
                        )
                    }
                }
            }
    }
}

@androidx.annotation.RequiresApi(33)
@Composable
private fun AnimatedBackdrop(
    kind: String,
    background: Color,
    accent: Color,
    phase: Float,
    parallax: Float,
) {
    val shader = remember {
        android.graphics.RuntimeShader(
            """
            uniform float2 bounds;
            uniform float phase, offset, metallic;
            layout(color) uniform half4 background;
            layout(color) uniform half4 tint;
            half4 main(float2 pixel) {
                float2 uv = (pixel + float2(offset, 0.0)) / max(bounds.y, 1.0);
                float angle = 6.283185 * phase;
                float wave = uv.y + 0.13 * sin(uv.x * 4.0 + angle) + 0.07 * cos(uv.x * 6.0 - angle);
                float clay = 0.5 + 0.5 * sin(wave * 12.0);
                float metal = pow(0.5 + 0.5 * cos((uv.x + wave) * 9.0 + angle), 4.0);
                float silk = 0.5 + 0.5 * sin(wave * 25.0 + 2.0 * sin(uv.x * 5.0 + angle));
            float field = metallic < 0.0 ? silk : mix(clay, metal, metallic);
            return mix(background, tint, half(0.08 + 0.32 * field));
            }
            """
                .trimIndent(),
        )
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(Modifier.fillMaxSize()) {
        shader.setFloatUniform("bounds", size.width, size.height)
        shader.setFloatUniform("phase", phase)
        shader.setFloatUniform("offset", parallax)
        shader.setFloatUniform(
            "metallic",
            when (kind) {
                "Silk" -> -1f
                "Soft metal" -> 1f
                else -> 0f
            },
        )
        shader.setColorUniform("background", background.toArgb())
        shader.setColorUniform(
            "tint",
            if (kind == "Soft metal") Color(0xff889299).toArgb() else accent.toArgb(),
        )
        drawRect(brush)
    }
}
