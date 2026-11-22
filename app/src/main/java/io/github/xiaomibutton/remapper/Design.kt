package io.github.xiaomibutton.remapper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
private fun remapperColors(): ColorScheme {
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colorResource(R.color.window_accent),
        onPrimary = colorResource(R.color.remapper_on_primary),
        primaryContainer = colorResource(R.color.remapper_primary_container),
        onPrimaryContainer = colorResource(R.color.remapper_on_primary_container),
        secondary = colorResource(R.color.remapper_secondary),
        onSecondary = colorResource(R.color.remapper_on_secondary),
        secondaryContainer = colorResource(R.color.remapper_secondary_container),
        onSecondaryContainer = colorResource(R.color.remapper_on_secondary_container),
        surface = colorResource(R.color.remapper_surface),
        background = colorResource(R.color.window_background),
        surfaceContainerLowest = colorResource(R.color.remapper_surface_container_lowest),
        surfaceContainer = colorResource(R.color.remapper_surface_container),
        surfaceContainerLow = colorResource(R.color.remapper_surface_container_low),
        surfaceContainerHigh = colorResource(R.color.remapper_surface_container_high),
        surfaceContainerHighest = colorResource(R.color.remapper_surface_container_highest),
        surfaceVariant = colorResource(R.color.remapper_surface_variant),
        onBackground = colorResource(R.color.remapper_on_background),
        onSurface = colorResource(R.color.remapper_on_surface),
        onSurfaceVariant = colorResource(R.color.remapper_on_surface_variant),
        outline = colorResource(R.color.remapper_outline),
        outlineVariant = colorResource(R.color.remapper_outline_variant),
        inverseSurface = colorResource(R.color.remapper_inverse_surface),
        inverseOnSurface = colorResource(R.color.remapper_inverse_on_surface),
        error = colorResource(R.color.remapper_error),
        onError = colorResource(R.color.remapper_on_error),
        errorContainer = colorResource(R.color.remapper_error_container),
        onErrorContainer = colorResource(R.color.remapper_on_error_container)
    )
}

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.35).sp),
    headlineSmall = TextStyle(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.65).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
)

internal val ScreenGutter = 20.dp
internal val ScreenWidth = 580.dp
internal val PanelShape = RoundedCornerShape(26.dp)
internal val TileShape = RoundedCornerShape(15.dp)

@Composable
internal fun RemapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = remapperColors(),
        typography = type,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = TileShape,
            large = PanelShape, extraLarge = RoundedCornerShape(32.dp)
        )
    ) { ProvideTextStyle(MaterialTheme.typography.bodyLarge, content) }
}

internal enum class Mark {
    BUTTON, REAR_SCREEN, PULSE, TUNE, SINGLE, DOUBLE, LONG, TRIPLE, NEXT, BACK, CLOSE, LINK, PLUS
}

internal val GestureSlot.mark: Mark
    get() = when (this) {
        GestureSlot.SINGLE -> Mark.SINGLE
        GestureSlot.DOUBLE -> Mark.DOUBLE
        GestureSlot.LONG -> Mark.LONG
        GestureSlot.TRIPLE -> Mark.TRIPLE
    }

@Composable
internal fun LineIcon(mark: Mark, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            fun line(x: Float, y: Float, xx: Float, yy: Float) {
                val mirror = rtl && (mark == Mark.NEXT || mark == Mark.BACK)
                drawLine(color, Offset(if (mirror) 24f - x else x, y),
                    Offset(if (mirror) 24f - xx else xx, yy), 1.8f, StrokeCap.Round)
            }
            when (mark) {
                Mark.BUTTON -> {
                    drawRoundRect(color, Offset(6f, 3f), Size(11f, 18f), CornerRadius(3f), style = Stroke(1.7f))
                    line(19.5f, 8f, 19.5f, 13f)
                    line(10f, 17.5f, 13f, 17.5f)
                }
                Mark.REAR_SCREEN -> {
                    drawRoundRect(color, Offset(6f, 2.5f), Size(12f, 19f), CornerRadius(3f), style = Stroke(1.7f))
                    drawRoundRect(color, Offset(8.4f, 5.2f), Size(7.2f, 5.7f), CornerRadius(1.15f), style = Stroke(1.4f))
                    line(10.5f, 18.5f, 13.5f, 18.5f)
                }
                Mark.PULSE -> {
                    line(2f, 13f, 7f, 13f)
                    line(7f, 13f, 10f, 5f)
                    line(10f, 5f, 14f, 19f)
                    line(14f, 19f, 17f, 11f)
                    line(17f, 11f, 22f, 11f)
                }
                Mark.TUNE -> {
                    line(3f, 8f, 21f, 8f)
                    line(3f, 16f, 21f, 16f)
                    drawCircle(color, 2.7f, Offset(9f, 8f))
                    drawCircle(color, 2.7f, Offset(15f, 16f))
                }
                Mark.SINGLE, Mark.DOUBLE, Mark.TRIPLE -> {
                    val count = when (mark) { Mark.SINGLE -> 1; Mark.DOUBLE -> 2; else -> 3 }
                    repeat(count) { i ->
                        val x = 12f + (i - (count - 1) / 2f) * 6f
                        drawRoundRect(color, Offset(x - 1.75f, 6f), Size(3.5f, 12f), CornerRadius(1.75f))
                    }
                }
                Mark.LONG -> drawRoundRect(color, Offset(4f, 10f), Size(16f, 4f), CornerRadius(2f))
                Mark.NEXT -> {
                    line(9f, 6f, 15f, 12f)
                    line(15f, 12f, 9f, 18f)
                }
                Mark.BACK -> {
                    line(5f, 12f, 19f, 12f)
                    line(5f, 12f, 11f, 6f)
                    line(5f, 12f, 11f, 18f)
                }
                Mark.CLOSE -> {
                    line(6f, 6f, 18f, 18f)
                    line(18f, 6f, 6f, 18f)
                }
                Mark.LINK -> {
                    drawRoundRect(color, Offset(3f, 9f), Size(8f, 6f), CornerRadius(3f), style = Stroke(1.7f))
                    drawRoundRect(color, Offset(13f, 9f), Size(8f, 6f), CornerRadius(3f), style = Stroke(1.7f))
                    line(8f, 12f, 16f, 12f)
                }
                Mark.PLUS -> {
                    line(5f, 12f, 19f, 12f)
                    line(12f, 5f, 12f, 19f)
                }
            }
        }
    }
}

@Composable
internal fun Headline(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground)
}

@Composable
internal fun GroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(start = 2.dp, top = 7.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier.fillMaxWidth(), shape = PanelShape, color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(content = content)
    }
}

@Composable
internal fun IconTile(mark: Mark, muted: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(40.dp).clip(TileShape).background(if (muted) colors.surfaceContainerLow else colors.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        LineIcon(mark, Modifier.size(22.dp), if (muted) colors.onSurfaceVariant else colors.onPrimaryContainer)
    }
}

@Composable
internal fun RowDivider(start: Int = 16) {
    HorizontalDivider(Modifier.padding(start = start.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SettingRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    mark: Mark? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val interaction = when {
        checked != null && onClick != null ->
            Modifier.toggleable(value = checked, role = Role.Switch, enabled = enabled) { onClick() }
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(interaction).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            leading != null -> leading()
            mark != null -> IconTile(mark, muted = !enabled)
            icon != null -> ActionGlyph(icon, muted = !enabled)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            if (!description.isNullOrBlank()) QuietText(description)
        }
        if (trailing != null) trailing()
        else if (onClick != null && enabled) LineIcon(Mark.NEXT, Modifier.size(16.dp), colors.onSurfaceVariant)
    }
}

@Composable
internal fun QuietText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) colors.error else colors.inverseSurface,
            contentColor = if (destructive) colors.onError else colors.inverseOnSurface
        )
    ) { Text(text) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)
    ) { Text(text) }
}

@Composable
internal fun EditorField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value, onValueChange, modifier, label = label, singleLine = singleLine, maxLines = maxLines,
        keyboardOptions = keyboardOptions, shape = TileShape,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

@Composable
internal fun RemapperDialog(
    onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest, title = title, text = text,
        confirmButton = confirmButton, dismissButton = dismissButton,
        shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    )
}

@Composable
internal fun EditorPage(
    title: String,
    context: String,
    atRoot: Boolean,
    back: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val lightBars = !isSystemInDarkTheme()
    Dialog(onDismissRequest = back, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = ScreenWidth).fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = 10.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val label = if (atRoot) "Close" else "Back"
                        Surface(
                            modifier = Modifier.size(48.dp).semantics { contentDescription = label },
                            onClick = back, shape = CircleShape, color = colors.surfaceContainer,
                            border = BorderStroke(1.dp, colors.outlineVariant)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                LineIcon(if (atRoot) Mark.CLOSE else Mark.BACK, Modifier.size(20.dp), colors.onSurface)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(context, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    content()
                }
            }
        }
    }
}
