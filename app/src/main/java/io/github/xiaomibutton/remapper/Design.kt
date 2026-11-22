package io.github.xiaomibutton.remapper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val light = lightColorScheme(
    primary = Color(0xFF2453C9), onPrimary = Color.White, primaryContainer = Color(0xFFE0E9FF),
    onPrimaryContainer = Color(0xFF153577), surface = Color(0xFFFAFAFC), background = Color(0xFFF3F4F7),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF2F3F7),
    onBackground = Color(0xFF181B22), onSurface = Color(0xFF181B22),
    onSurfaceVariant = Color(0xFF505865), outlineVariant = Color(0xFFDCE0E7)
)
private val dark = darkColorScheme(
    primary = Color(0xFFAEC6FF), primaryContainer = Color(0xFF283D67), onPrimaryContainer = Color(0xFFDBE6FF),
    onPrimary = Color(0xFF0D285E), surface = Color(0xFF17191E), background = Color(0xFF101216),
    surfaceContainer = Color(0xFF1F2229), surfaceContainerLow = Color(0xFF171A20),
    onBackground = Color(0xFFECEDF2), onSurface = Color(0xFFECEDF2),
    onSurfaceVariant = Color(0xFFBBC1CE), outlineVariant = Color(0xFF3B414D)
)

@Composable
internal fun RemapperTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)

internal enum class Mark { BUTTON, PULSE, TUNE, SINGLE, DOUBLE, LONG, TRIPLE, NEXT }

@Composable
internal fun LineIcon(mark: Mark, modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            fun line(x: Int, y: Int, xx: Int, yy: Int) =
                drawLine(color, Offset(x.toFloat(), y.toFloat()), Offset(xx.toFloat(), yy.toFloat()), 1.8f, StrokeCap.Round)
            when (mark) {
                Mark.BUTTON -> {
                    drawRoundRect(color, Offset(6f, 3f), Size(12f, 18f), CornerRadius(3f), style = Stroke(1.7f))
                    line(18, 8, 18, 13)
                    line(10, 17, 14, 17)
                }
                Mark.PULSE -> { line(2, 13, 7, 13); line(7, 13, 10, 5); line(10, 5, 14, 19); line(14, 19, 17, 11); line(17, 11, 22, 11) }
                Mark.TUNE -> {
                    line(3, 7, 21, 7); line(3, 16, 21, 16)
                    drawCircle(color, 3.2f, Offset(9f, 7f)); drawCircle(color, 3.2f, Offset(15f, 16f))
                }
                Mark.SINGLE, Mark.DOUBLE, Mark.TRIPLE -> {
                    val count = if (mark == Mark.SINGLE) 1 else if (mark == Mark.DOUBLE) 2 else 3
                    repeat(count) { i ->
                        val x = 12f + (i - (count - 1) / 2f) * 7f
                        drawRoundRect(color, Offset(x - 2f, 6f), Size(4f, 12f), CornerRadius(2f))
                    }
                }
                Mark.LONG -> drawRoundRect(color, Offset(3f, 9.5f), Size(18f, 5f), CornerRadius(2.5f))
                Mark.NEXT -> { line(9, 6, 15, 12); line(15, 12, 9, 18) }
            }
        }
    }
}

@Composable
internal fun Headline(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1.1).sp, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
internal fun GroupTitle(title: String) {
    Text(title, Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer), content = content)
}

@Composable
internal fun SettingRow(
    title: String, description: String? = null, onClick: (() -> Unit)? = null, mark: Mark? = null,
    checked: Boolean? = null, trailing: (@Composable () -> Unit)? = null
) {
    val interaction = when {
        checked != null && onClick != null -> Modifier.toggleable(value = checked, role = Role.Switch) { onClick() }
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(Modifier.fillMaxWidth().then(interaction).heightIn(min = 48.dp)
        .padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (mark != null) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                LineIcon(mark, Modifier.size(23.dp), MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing() else if (onClick != null)
            LineIcon(Mark.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun QuietText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

